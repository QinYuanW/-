package com.iot.platform.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.common.Result;
import com.iot.platform.dto.DeviceVO;
import com.iot.platform.dto.TelemetryPointVO;
import com.iot.platform.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping("/devices")
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        Page<DeviceVO> result = deviceService.listDevices(page, size);
        return Result.ok(Map.of("total", result.getTotal(), "items", result.getRecords()));
    }

    @GetMapping("/devices/{deviceKey}/telemetry/latest")
    public Result<Map<String, Object>> latestTelemetry(@PathVariable String deviceKey) {
        return Result.ok(deviceService.getLatestTelemetry(deviceKey));
    }

    @GetMapping("/devices/{deviceKey}/telemetry")
    public Result<Map<String, Object>> historyTelemetry(@PathVariable String deviceKey,
                                                        @RequestParam String property,
                                                        @RequestParam(required = false) Long start,
                                                        @RequestParam(required = false) Long end) {
        List<TelemetryPointVO> items = deviceService.getTelemetryHistory(deviceKey, property, start, end);
        return Result.ok(Map.of("items", items));
    }
}