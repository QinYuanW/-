package com.iot.platform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.dto.DeviceVO;
import com.iot.platform.dto.TelemetryPointVO;

import java.util.Map;

public interface DeviceService {
    Page<DeviceVO> listDevices(int page, int size);
    Map<String, Object> getLatestTelemetry(String deviceKey);
    java.util.List<TelemetryPointVO> getTelemetryHistory(String deviceKey, String property, Long start, Long end);
}