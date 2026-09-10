package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iot.platform.entity.Device;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final DeviceMapper deviceMapper; // 注入设备映射器
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean authenticate(String deviceKey, String password) {
        // 1. 查 Redis 缓存
        String cached = redisTemplate.opsForValue().get("auth:" + deviceKey);
        if (cached != null) {
            return cached.equals(password);
        }

        // 2. 缓存未命中，查 device 表
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return false;
        }

        // 3. 回填缓存
        redisTemplate.opsForValue().set("auth:" + deviceKey, device.getDeviceSecret());
        return device.getDeviceSecret().equals(password);
    }
}