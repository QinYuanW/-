package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.client.EmqxClient;
import com.iot.platform.dto.CommandRequest;
import com.iot.platform.dto.CommandVO;
import com.iot.platform.entity.Command;
import com.iot.platform.entity.Device;
import com.iot.platform.mapper.CommandMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.service.CommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandServiceImpl implements CommandService {

    private final CommandMapper commandMapper;
    private final DeviceMapper deviceMapper;
    private final EmqxClient emqxClient;
    private final ObjectMapper objectMapper;

    @Override
    public CommandVO dispatch(String deviceKey, CommandRequest request) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            throw new IllegalArgumentException("device not found");
        }

        // 1. 落库 status=0 待下发
        Command cmd = new Command();
        cmd.setDeviceId(device.getId());
        cmd.setCommandType(request.getCommandType());
        cmd.setPayload(toJson(request.getPayload()));
        cmd.setStatus(0);
        cmd.setRequestId(UUID.randomUUID().toString());
        cmd.setCreatedAt(LocalDateTime.now());
        cmd.setUpdatedAt(LocalDateTime.now());
        commandMapper.insert(cmd);

        // 2. 调 EMQX REST API 推送 down/{device_key}/command（携带 request_id 供设备 ACK）
        String message = buildDownMessage(cmd, request);
        boolean published = emqxClient.publish("down/" + deviceKey + "/command", message);

        // 3. 更新指令状态：推送成功 status=1 已下发；失败 status=3
        int status = published ? 1 : 3;
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
        if (cmd.getStatus() != 1) {
            log.warn("ack ignored: request_id={}, current_status={}", requestId, cmd.getStatus());
            return CommandVO.from(cmd);
        }
        // 4. 设备回报：status=2 已执行 / 3 失败（条件更新防并发竞争）
        int status = success ? 2 : 3;
        int rows = commandMapper.update(null, new LambdaUpdateWrapper<Command>()
                .set(Command::getStatus, status)
                .set(Command::getAckAt, LocalDateTime.now())
                .set(Command::getUpdatedAt, LocalDateTime.now())
                .eq(Command::getId, cmd.getId())
                .eq(Command::getStatus, 1));
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
                .set(Command::getStatus, 4)
                .set(Command::getUpdatedAt, LocalDateTime.now())
                .eq(Command::getStatus, 1)
                .lt(Command::getSentAt, threshold));
        if (updated > 0) {
            log.info("command timeout scan: {} commands -> TIMEOUT(4)", updated);
        }
    }

    private String toJson(Object payload) {
        try {
            return payload != null ? objectMapper.writeValueAsString(payload) : "{}";
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    private String buildDownMessage(Command cmd, CommandRequest request) {
        try {
            Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("request_id", cmd.getRequestId());
            message.put("command_type", cmd.getCommandType());
            message.put("payload", request.getPayload() != null ? request.getPayload() : Map.of());
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("build down message failed: {}", e.getMessage());
            return "{}";
        }
    }
}
