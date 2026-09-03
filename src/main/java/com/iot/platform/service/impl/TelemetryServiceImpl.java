package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceTelemetry;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.DeviceTelemetryMapper;
import com.iot.platform.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryServiceImpl implements TelemetryService {

    private final DeviceMapper deviceMapper;
    private final DeviceTelemetryMapper telemetryMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Async("telemetryExecutor")
    public void process(TelemetryRequest request) {
        try {
            log.info("telemetry received: device_key={}, props={}", request.getDeviceKey(), request.getProperties());
            // 1. device_key → device_id 映射（Redis 缓存）
            Long deviceId = resolveDeviceId(request.getDeviceKey());
            if (deviceId == null) {
                log.warn("unknown device_key: {}", request.getDeviceKey());
                return;
            }

            // 2. 遍历 properties 逐条入库
            LocalDateTime ts = request.getTs() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochMilli(request.getTs()), ZoneId.of("Asia/Shanghai"))
                    : LocalDateTime.now();

            for (Map.Entry<String, Object> entry : request.getProperties().entrySet()) {
                DeviceTelemetry t = new DeviceTelemetry();
                t.setDeviceId(deviceId);
                t.setPropertyKey(entry.getKey());
                t.setValue(String.valueOf(entry.getValue()));
                t.setValueType(inferValueType(entry.getValue()));
                t.setTs(ts);
                telemetryMapper.insert(t);
            }

            // 3. 刷新在线状态
            deviceMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Device>()
                    .set(Device::getOnlineStatus, 1)
                    .set(Device::getLastOnlineAt, LocalDateTime.now())
                    .eq(Device::getId, deviceId));
            log.info("telemetry saved: device_key={}, props={}", request.getDeviceKey(), request.getProperties().size());
        } catch (Exception e) {
            log.error("telemetry process failed: device_key={}", request.getDeviceKey(), e);
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