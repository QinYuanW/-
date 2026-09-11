package com.iot.platform.service.impl;

import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;
import com.iot.platform.service.CommandService;
import com.iot.platform.service.ShadowService;
import com.iot.platform.service.ShadowSyncService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * E1 影子同步实现：设备上线 → ① 补发离线指令 ② 下发 desired/reported 差异。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowSyncServiceImpl implements ShadowSyncService {

    private final CommandService commandService;
    private final ShadowService shadowService;
    private final MeterRegistry meterRegistry;

    /** 属性设置指令类型码（与 ESP32 固件约定：payload 为「属性名 → 值」扁平映射） */
    private static final int CMD_TYPE_PROPERTY_SET = 1;

    @Override
    public void onDeviceOnline(Long deviceId, String deviceKey) {
        try {
            // ① 先记下「待补发指令已覆盖的属性」——补发后这些指令会迁出 status=0，必须先取
            Set<String> covered = commandService.pendingPropertyKeys(deviceId);

            // ② 补发离线期间挂起的指令（按创建顺序，受单轮上限与保质期约束）
            int replayed = commandService.replayPending(deviceId, deviceKey);

            // ③ 对齐期望值：desired 与 reported 不一致、**且未被本次补发指令覆盖**的属性下发给设备。
            //    剔除 covered 是关键 —— applyDesired 已经为目标值生成过指令（离线排队/在线直发），
            //    不剔除的话上线瞬间会对同一属性重复下发（实测复现：同一 switch=true 连发两条）。
            Map<String, Object> delta = new LinkedHashMap<>(shadowService.getDelta(deviceKey));
            delta.keySet().removeAll(covered);
            if (!delta.isEmpty()) {
                commandService.dispatch(deviceKey, propertySetCommand(delta));
                log.info("shadow delta dispatched on online: device_key={}, delta={}", deviceKey, delta);
            }

            if (replayed > 0 || !delta.isEmpty()) {
                meterRegistry.counter("iot_shadow_sync_total").increment();
                log.info("shadow sync done: device_key={}, replayed={}, delta_size={}",
                        deviceKey, replayed, delta.size());
            }
        } catch (Exception e) {
            // 影子同步失败绝不上抛：设备上线事件已在遥测链路中处理完毕，
            // 这里失败只是「增强能力未生效」，不能让它把遥测拖进重试/DLT。
            meterRegistry.counter("iot_shadow_sync_failures_total").increment();
            log.error("shadow sync on device online failed: device_key={}", deviceKey, e);
        }
    }

    @Override
    public Map<String, Object> applyDesired(String deviceKey, Map<String, Object> desired) {
        Map<String, Object> accepted = shadowService.setDesired(deviceKey, desired);
        Map<String, Object> delta = shadowService.getDelta(deviceKey);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", accepted);
        out.put("delta", delta);
        if (delta.isEmpty()) {
            out.put("command_status", null);
            return out;
        }
        try {
            CommandVO vo = commandService.dispatch(deviceKey, propertySetCommand(delta));
            // 0 = 待下发（设备离线，已挂起，上线自动补发）/ 1 = 已下发
            out.put("command_request_id", vo.getRequestId());
            out.put("command_status", vo.getStatus());
        } catch (Exception e) {
            // 设备不存在等业务异常：desired 已写入成功，指令未产生
            log.warn("shadow desired dispatch failed: device_key={}, err={}", deviceKey, e.getMessage());
            out.put("command_status", null);
            out.put("dispatch_error", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private CommandRequest propertySetCommand(Map<String, Object> properties) {
        CommandRequest request = new CommandRequest();
        request.setCommandType(CMD_TYPE_PROPERTY_SET);
        request.setPayload(properties);
        return request;
    }
}
