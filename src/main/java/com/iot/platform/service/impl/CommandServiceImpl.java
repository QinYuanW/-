package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.client.EmqxClient;
import com.iot.platform.common.BizException;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;
import com.iot.platform.entity.Command;
import com.iot.platform.entity.Device;
import com.iot.platform.mapper.CommandMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.service.AuthService;
import com.iot.platform.service.CommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandServiceImpl implements CommandService {

    private final CommandMapper commandMapper;
    private final DeviceMapper deviceMapper;
    private final EmqxClient emqxClient;
    private final ObjectMapper objectMapper;
    /** F2 停用拦截（复用认证缓存中的产品启用状态） */
    private final AuthService authService;

    /** 状态常量：0 待下发 / 1 已下发 / 2 已执行 / 3 失败 / 4 超时 */
    private static final int ST_PENDING = 0;
    private static final int ST_SENT = 1;
    private static final int ST_FAILED = 3;
    private static final int ST_TIMEOUT = 4;

    /** E1：单轮补发上限，防止设备长期离线积累大量待下发指令后「补发风暴」 */
    private static final int MAX_REPLAY_PER_ROUND = 20;
    /** E1：待下发指令的保质期，超过则不再补发、直接置超时 */
    private static final long PENDING_TTL_HOURS = 24;

    @Override
    public CommandVO dispatch(String deviceKey, CommandRequest request) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            throw new IllegalArgumentException("device not found");
        }

        // F2 停用拦截：被停用的设备不应接收任何指令。
        // 注意这里**先于**「离线即失败」的判断 —— 停用是配置态问题（需人工在页面恢复），
        // 与「设备暂时不在线」不是一类事，错误码必须区分开（4006 vs 4005）。
        if (device.getStatus() == null || device.getStatus() != 1
                || !authService.isProductEnabled(device.getProductKey())) {
            throw new BizException(ErrorCode.DEVICE_DISABLED,
                    "设备已停用，指令未下发（可在「设备」页重新启用）");
        }

        // 0. E1 语义分家（2026-09-12 汤园决策）：命令 ≠ 期望状态
        //    · 命令语义（缺省）：瞬时动作，设备离线 → 直接失败。过期的上下文不该被延迟执行
        //      （想象离线期间下达的"关阀"在设备上线后被突然执行）。
        //    · 期望状态语义（queue_if_offline=true，仅影子期望值路径）：离线挂起，上线补发。
        if (!isOnline(device) && !Boolean.TRUE.equals(request.getQueueIfOffline())) {
            throw new BizException(ErrorCode.DEVICE_OFFLINE,
                    "设备不在线，指令未下发（如需离线生效，请使用「设备影子-期望值」）");
        }

        // 1. 落库 status=0 待下发
        Command cmd = new Command();
        cmd.setDeviceId(device.getId());
        cmd.setCommandType(request.getCommandType());
        cmd.setPayload(toJson(request.getPayload()));
        cmd.setStatus(ST_PENDING);
        cmd.setRequestId(UUID.randomUUID().toString());
        cmd.setCreatedAt(LocalDateTime.now());
        cmd.setUpdatedAt(LocalDateTime.now());
        commandMapper.insert(cmd);

        // 2. E1 期望状态语义的挂起闸门：设备离线时保持 status=0，不 publish
        //    （MQTT publish 是 fire-and-forget，EMQX 对无人订阅的 topic 依然返回成功，
        //     且 EmqxClient 为 retain=false——离线硬发会谎报 status=1，60s 后只能变超时）
        if (!isOnline(device)) {
            log.info("command queued (device offline): request_id={}, device_key={}",
                    cmd.getRequestId(), deviceKey);
            return CommandVO.from(cmd);
        }

        // 3. 在线：调 EMQX REST API 推送 down/{device_key}/command（携带 request_id 供设备 ACK）
        boolean published = emqxClient.publish("down/" + deviceKey + "/command", buildDownMessage(cmd));

        // 4. 更新指令状态：推送成功 status=1 已下发；失败 status=3
        int status = published ? ST_SENT : ST_FAILED;
        LocalDateTime now = LocalDateTime.now();
        commandMapper.update(null, new LambdaUpdateWrapper<Command>()
                .set(Command::getStatus, status)
                .set(published, Command::getSentAt, now)
                .set(Command::getUpdatedAt, now)
                .eq(Command::getId, cmd.getId()));
        cmd.setStatus(status);
        cmd.setSentAt(published ? now : null);

        log.info("command dispatched: request_id={}, device_key={}, status={}",
                cmd.getRequestId(), deviceKey, status);
        return CommandVO.from(cmd);
    }

    @Override
    public int replayPending(Long deviceId, String deviceKey) {
        List<Command> pending = commandMapper.selectList(new LambdaQueryWrapper<Command>()
                .eq(Command::getDeviceId, deviceId)
                .eq(Command::getStatus, ST_PENDING)
                .orderByAsc(Command::getCreatedAt)
                .last("LIMIT " + MAX_REPLAY_PER_ROUND));
        if (pending.isEmpty()) {
            return 0;
        }

        LocalDateTime expireBefore = LocalDateTime.now().minusHours(PENDING_TTL_HOURS);
        int sent = 0;
        for (Command c : pending) {
            if (c.getCreatedAt() != null && c.getCreatedAt().isBefore(expireBefore)) {
                if (migrate(c.getId(), ST_TIMEOUT, false)) {
                    log.info("pending command expired -> TIMEOUT(4): request_id={}", c.getRequestId());
                }
                continue;
            }
            boolean ok = emqxClient.publish("down/" + deviceKey + "/command", buildDownMessage(c));
            if (migrate(c.getId(), ok ? ST_SENT : ST_FAILED, ok) && ok) {
                sent++;
            }
        }
        log.info("offline command replay: device_key={}, candidates={}, sent={}",
                deviceKey, pending.size(), sent);
        return sent;
    }

    @Override
    public List<CommandVO> listPending(String deviceKey) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return Collections.emptyList();
        }
        return commandMapper.selectList(new LambdaQueryWrapper<Command>()
                        .eq(Command::getDeviceId, device.getId())
                        .eq(Command::getStatus, ST_PENDING)
                        .orderByAsc(Command::getCreatedAt))
                .stream().map(CommandVO::from).collect(Collectors.toList());
    }

    @Override
    public Set<String> pendingPropertyKeys(Long deviceId) {
        List<Command> pending = commandMapper.selectList(new LambdaQueryWrapper<Command>()
                .eq(Command::getDeviceId, deviceId)
                .eq(Command::getStatus, ST_PENDING));
        Set<String> keys = new LinkedHashSet<>();
        for (Command c : pending) {
            keys.addAll(parsePayload(c.getPayload()).keySet());
        }
        return keys;
    }

    @Override
    public CommandVO getStatus(String requestId) {
        Command cmd = commandMapper.selectOne(
                new LambdaQueryWrapper<Command>().eq(Command::getRequestId, requestId));
        return cmd != null ? CommandVO.from(cmd) : null;
    }

    @Override
    public CommandVO ack(String requestId, boolean success) {
        Command cmd = commandMapper.selectOne(
                new LambdaQueryWrapper<Command>().eq(Command::getRequestId, requestId));
        if (cmd == null) {
            return null;
        }
        // 幂等/防重：仅 status=1（已下发）允许确认；2/3/4 已终态，重复 ack 直接忽略
        if (cmd.getStatus() != ST_SENT) {
            log.warn("ack ignored: request_id={}, current_status={}", requestId, cmd.getStatus());
            return CommandVO.from(cmd);
        }
        // 4. 设备回报：status=2 已执行 / 3 失败（条件更新防并发竞争）
        int status = success ? 2 : ST_FAILED;
        int rows = commandMapper.update(null, new LambdaUpdateWrapper<Command>()
                .set(Command::getStatus, status)
                .set(Command::getAckAt, LocalDateTime.now())
                .set(Command::getUpdatedAt, LocalDateTime.now())
                .eq(Command::getId, cmd.getId())
                .eq(Command::getStatus, ST_SENT));
        if (rows == 0) {
            // 并发下已被超时扫描或另一条 ack 抢先变更
            log.warn("ack race ignored: request_id={}", requestId);
            return CommandVO.from(commandMapper.selectById(cmd.getId()));
        }
        cmd.setStatus(status);
        cmd.setAckAt(LocalDateTime.now());
        log.info("command acked: request_id={}, status={}", requestId, status);
        return CommandVO.from(cmd);
    }

    /**
     * 超时扫描：status=1 已下发但超过 60s 无设备回报 → 置为 4 超时
     * 走 idx_status_sent 索引（status, sent_at）
     */
    @Scheduled(fixedDelay = 30_000)
    public void timeoutScan() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(60);
        int updated = commandMapper.update(null, new LambdaUpdateWrapper<Command>()
                .set(Command::getStatus, ST_TIMEOUT)
                .set(Command::getUpdatedAt, LocalDateTime.now())
                .eq(Command::getStatus, ST_SENT)
                .lt(Command::getSentAt, threshold));
        if (updated > 0) {
            log.info("command timeout scan: {} commands -> TIMEOUT(4)", updated);
        }
    }

    // ------------------------------------------------------------------ 内部

    private boolean isOnline(Device device) {
        return device.getOnlineStatus() != null && device.getOnlineStatus() == 1;
    }

    /**
     * 条件状态迁移：仅当当前为 {@code status=0} 时才迁移，保证补发幂等
     * （上线瞬间重复触发 replayPending 不会把已下发的指令再迁一次）。
     *
     * @return 是否真的发生了迁移
     */
    private boolean migrate(Long id, int target, boolean setSentAt) {
        LocalDateTime now = LocalDateTime.now();
        int rows = commandMapper.update(null, new LambdaUpdateWrapper<Command>()
                .set(Command::getStatus, target)
                .set(setSentAt, Command::getSentAt, now)
                .set(Command::getUpdatedAt, now)
                .eq(Command::getId, id)
                .eq(Command::getStatus, ST_PENDING));
        return rows > 0;
    }

    private String toJson(Object payload) {
        try {
            return payload != null ? objectMapper.writeValueAsString(payload) : "{}";
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    /**
     * 构造下行报文（补发路径也能复用，因为 {@code payload} 已序列化进 command 表）。
     *
     * <p>格式（与 ESP32 固件 {@code handle_command} 对齐）：
     * {@code {"request_id":"...","command_type":1,"payload":{"switch":true,"temperature":26}}}
     * —— payload 是「属性名 → 值」的扁平映射，固件会遍历其所有子节点逐属性执行。
     */
    private String buildDownMessage(Command cmd) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("request_id", cmd.getRequestId());
            message.put("command_type", cmd.getCommandType());
            message.put("payload", parsePayload(cmd.getPayload()));
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("build down message failed: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("parse command payload failed, raw={}, err={}", json, e.getMessage());
            return Collections.emptyMap();
        }
    }
}
