package com.iot.platform.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.common.Result;
import com.iot.platform.dto.DeviceRegisterRequest;
import com.iot.platform.dto.DeviceRegisterVO;
import com.iot.platform.dto.DeviceVO;
import com.iot.platform.dto.TelemetryPointVO;
import com.iot.platform.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
/**
 * @RestController 相当于你同时加了这两个注解
 * @Controller // 告诉Spring：这是处理HTTP请求的「Web组件」
 * @ResponseBody // 告诉Spring：所有方法的返回值直接当「HTTP响应体」返回（比如转成JSON）
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor  // 自动生成构造器：public DeviceController(DeviceService deviceService)
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping("/devices")
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String keyword) {
        Page<DeviceVO> result = deviceService.listDevices(page, size, keyword);
        return Result.ok(Map.of("total", result.getTotal(), "items", result.getRecords()));
    }

    @PostMapping("/devices")
    public Result<DeviceRegisterVO> register(@RequestBody @Valid DeviceRegisterRequest request) {
        return Result.ok(deviceService.register(request));
    }

    @PostMapping("/devices/batch")
    public Result<Map<String, Object>> registerBatch(@RequestBody @Valid DeviceRegisterRequest request) {
        List<DeviceRegisterVO> items = deviceService.registerBatch(request);
        return Result.ok(Map.of("total", items.size(), "items", items));
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

    @GetMapping("/devices/{deviceKey}/tsl")
    public Result<Map<String, Object>> tsl(@PathVariable String deviceKey) {
        Map<String, Object> tsl = deviceService.getTsl(deviceKey);
        if (tsl.isEmpty()) {
            return Result.fail(ErrorCode.DEVICE_NOT_FOUND, "设备不存在或未配置物模型");
        }
        return Result.ok(tsl);
    }
}