package com.iot.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.dto.DeviceVO;
import com.iot.platform.dto.TelemetryPointVO;
import com.iot.platform.entity.Device;
import com.iot.platform.entity.DeviceTelemetry;
import com.iot.platform.mapper.DeviceMapper;
import com.iot.platform.mapper.DeviceTelemetryMapper;
import com.iot.platform.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceMapper deviceMapper;
    private final DeviceTelemetryMapper telemetryMapper;

    @Override
    public Page<DeviceVO> listDevices(int page, int size) {
        Page<Device> devicePage = new Page<>(page, size);
        deviceMapper.selectPage(devicePage, new LambdaQueryWrapper<Device>()
                .orderByDesc(Device::getCreatedAt));

        Page<DeviceVO> voPage = new Page<>(page, size, devicePage.getTotal());
        voPage.setRecords(devicePage.getRecords().stream().map(DeviceVO::from).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public Map<String, Object> getLatestTelemetry(String deviceKey) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return Collections.emptyMap();
        }
        return new HashMap<>(telemetryMapper.selectLatestByDevice(device.getId()));
    }

    @Override
    public List<TelemetryPointVO> getTelemetryHistory(String deviceKey, String property, Long start, Long end) {
        Device device = deviceMapper.selectOne(
                new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (device == null) {
            return Collections.emptyList();
        }

        LocalDateTime startTime = start != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneId.of("Asia/Shanghai"))
                : LocalDateTime.now().minusHours(1);
        LocalDateTime endTime = end != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(end), ZoneId.of("Asia/Shanghai"))
                : LocalDateTime.now();

        List<DeviceTelemetry> list = telemetryMapper.selectList(
                new LambdaQueryWrapper<DeviceTelemetry>()
                        .eq(DeviceTelemetry::getDeviceId, device.getId())
                        .eq(DeviceTelemetry::getPropertyKey, property)
                        .between(DeviceTelemetry::getTs, startTime, endTime)
                        .orderByAsc(DeviceTelemetry::getTs));

        return list.stream().map(t -> {
            TelemetryPointVO vo = new TelemetryPointVO();
            vo.setTs(t.getTs().atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
            vo.setValue(t.getValue());
            return vo;
        }).collect(Collectors.toList());
    }
}