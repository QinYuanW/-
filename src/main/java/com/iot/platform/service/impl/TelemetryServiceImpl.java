package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.iot.platform.common.PermanentTelemetryException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceLog;
import com.iot.platform.entity.DeviceTelemetry;
import com.iot.platform.mapper.DeviceLogMapper;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.DeviceTelemetryMapper;
import com.iot.platform.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryServiceImpl implements TelemetryService {

    private final DeviceMapper deviceMapper;
    private final DeviceTelemetryMapper telemetryMapper;
    private final DeviceLogMapper deviceLogMapper;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Timer telemetryProcessTimer;

    /** 设备在线状态聚合刷新窗口：窗口内每设备只写一次 device 表，缓和高并发 hot-row 锁竞争（BUG-17 §3.3） */
    private static final Duration ONLINE_FLUSH_WINDOW = Duration.ofSeconds(5);

    @Override
    @Async("telemetryExecutor") // 历史链路（EMQX→@Async 直推）兼容包装；D1 后由 Kafka 消费者调 processSync
    public void process(TelemetryRequest request) {
        try {
            processSync(request);
        } catch (Exception e) {
            // 异步链路没有重试框架兜底，异常到线程池只会变成噪音日志；失败计数已在 processSync 内累加
            log.error("async telemetry process failed: device_key={}", request.getDeviceKey(), e);
        }
    }

    /**
     * D2-2：失败一律抛异常，交由 {@code @RetryableTopic} 分级（永久 → DLT / 瞬时 → 退避重试）。
     * 不再返回 boolean —— 那会让调用方"跳过 ack"，被后一条成功消息的 ack 越过而静默丢数据（BUG-21）。
     */
    @Override
    public void processSync(TelemetryRequest request) {
        // 埋点：上报→入库 全链路耗时（B5）
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            log.info("telemetry received: device_key={}, props={}", request.getDeviceKey(), request.getProperties());
            // 1. device_key → device_id 映射（Redis 缓存）
            Long deviceId = resolveDeviceId(request.getDeviceKey());
            if (deviceId == null) {
                // 永久失败：设备从未注册，重试多少次都是同一结果 → 不值一次重试，直接进死信
                throwFatal("unknown device_key: " + request.getDeviceKey());
            }

            // 2. 脏报文（缺属性/空属性）同样不会自愈，属永久失败
            if (request.getProperties() == null || request.getProperties().isEmpty()) {
                throwFatal("empty properties: device_key=" + request.getDeviceKey());
            }

            // 3. 批量入库：同一上报报文的多个属性拼成一条 multi-values INSERT（BUG-17 §3.2）
            LocalDateTime ts = request.getTs() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochMilli(request.getTs()), ZoneId.of("Asia/Shanghai"))
                    : LocalDateTime.now();

            List<DeviceTelemetry> telemList = new ArrayList<>(request.getProperties().size());
            for (Map.Entry<String, Object> entry : request.getProperties().entrySet()) {
                DeviceTelemetry t = new DeviceTelemetry();
                t.setDeviceId(deviceId);
                t.setPropertyKey(entry.getKey());
                t.setValue(String.valueOf(entry.getValue()));
                t.setValueType(inferValueType(entry.getValue()));
                t.setTs(ts);
                telemList.add(t);
            }
            if (!telemList.isEmpty()) {
                telemetryMapper.insertBatch(telemList);
            }

            // 4. 时间窗聚合刷新在线状态：5s 窗口内每设备只写一次 device 表（BUG-17 §3.3），
            //    跨 worker 用 Redis SETNX 原子协调，消除高并发 hot-row 锁竞争。
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent("onl:" + deviceId, "1", ONLINE_FLUSH_WINDOW);
            if (Boolean.TRUE.equals(first)) {
                refreshOnline(deviceId);
            }
            log.info("telemetry saved: device_key={}, props={}", request.getDeviceKey(), request.getProperties().size());
        } catch (PermanentTelemetryException e) {
            throw e; // 交给 @RetryableTopic 的 exclude → 跳过重试直达 DLT
        } catch (Exception e) {
            // 瞬时失败（DB 连接断开 / 超时 / 死锁等）：标记为可重试，退避后重来
            meterRegistry.counter("iot_telemetry_process_failures_total", "result", "retryable").increment();
            log.error("telemetry process failed (retryable): device_key={}", request.getDeviceKey(), e);
            throw new IllegalStateException("telemetry process failed: device_key=" + request.getDeviceKey(), e);
        } finally {
            sample.stop(telemetryProcessTimer);
        }
    }

    /** 记一次永久失败并抛出（不入重试阶梯）。计数供 Prometheus 观测（B6 告警已按 result 全量聚合，加标签不影响）。 */
    private void throwFatal(String message) {
        meterRegistry.counter("iot_telemetry_process_failures_total", "result", "permanent").increment();
        log.warn("telemetry permanent failure: {}", message);
        throw new PermanentTelemetryException(message);
    }

    /** 在线状态写入：条件更新 0→1 视为上线转换并写上线日志；已在线仅刷新 last_online_at */
    private void refreshOnline(Long deviceId) {
        LocalDateTime now = LocalDateTime.now();
        int transitioned = deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                .set(Device::getOnlineStatus, 1)
                .set(Device::getLastOnlineAt, now)
                .eq(Device::getId, deviceId)
                .eq(Device::getOnlineStatus, 0));
        if (transitioned > 0) {
            DeviceLog logEntry = new DeviceLog();
            logEntry.setDeviceId(deviceId);
            logEntry.setLogType(1);
            logEntry.setContent("设备上线（收到上报）");
            logEntry.setCreatedAt(now);
            deviceLogMapper.insert(logEntry);
        } else {
            deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                    .set(Device::getLastOnlineAt, now)
                    .eq(Device::getId, deviceId));
        }
    }

    private Long resolveDeviceId(String deviceKey) {
        String cached = redisTemplate.opsForValue().get("did:" + deviceKey);
        if (cached != null) {
            return Long.valueOf(cached);
        }
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device != null) {
            redisTemplate.opsForValue().set("did:" + deviceKey, String.valueOf(device.getId()));
            return device.getId();
        }
        return null;
    }

    private int inferValueType(Object value) {
        if (value instanceof Number) return 1;
        if (value instanceof Boolean) return 3;
        return 2;
    }
}