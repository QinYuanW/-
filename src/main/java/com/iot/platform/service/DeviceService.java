package com.iot.platform.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.dto.DeviceRegisterRequest;
import com.iot.platform.dto.DeviceRegisterVO;
import com.iot.platform.dto.DeviceVO;
import com.iot.platform.dto.TelemetryPointVO;

import java.util.List;
import java.util.Map;

public interface DeviceService {
    Page<DeviceVO> listDevices(int page, int size, String keyword);
    Map<String, Object> getLatestTelemetry(String deviceKey);
    List<TelemetryPointVO> getTelemetryHistory(String deviceKey, String property, Long start, Long end);
    Map<String, Object> getTsl(String deviceKey);
    DeviceRegisterVO register(DeviceRegisterRequest request);
    List<DeviceRegisterVO> registerBatch(DeviceRegisterRequest request);
}