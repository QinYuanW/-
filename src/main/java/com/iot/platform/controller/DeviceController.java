package com.iot.platform.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iot.platform.common.BizException;
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
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(name = "product_key", required = false) String productKey,
                                            @RequestParam(name = "online_status", required = false) Integer onlineStatus,
                                            @RequestParam(name = "group_id", required = false) Long groupId,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String order) {
        Page<DeviceVO> result = deviceService.listDevices(
                page, size, keyword, productKey, onlineStatus, groupId, sort, order);
        return Result.ok(Map.of("total", result.getTotal(), "items", result.getRecords()));
    }

    /**
     * 评审 4.8：修改设备名称（body {@code {"device_name": "..."}}）。
     *
     * <p>与启停（{@link #updateStatus}）、删除（{@link #deleteBatch}）语义分立：
     * 改名是纯标识变更，不影响链路；启停保留数据可恢复；删除不可逆。
     */
    @PutMapping("/devices/{deviceKey}")
    public Result<Map<String, Object>> rename(@PathVariable String deviceKey,
                                              @RequestBody Map<String, Object> body) {
        Object name = body.get("device_name");
        if (name == null || String.valueOf(name).isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 device_name");
        }
        boolean updated = deviceService.renameDevice(deviceKey, String.valueOf(name));
        return Result.ok(Map.of("updated", updated));
    }

    /**
     * F2 批量启停设备：body {@code {"device_keys": [...], "status": 0|1}}。
     *
     * <p>停用后设备**立即**无法 MQTT 接入（认证回调校验 status，并同步清认证缓存）；
     * **与删除的区别**是保留全部数据（遥测/日志/影子/指令），可一键恢复。
     */
    @PutMapping("/devices/status")
    public Result<Map<String, Object>> updateStatus(@RequestBody Map<String, Object> body) {
        Object status = body.get("status");
        if (status == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "缺少 status（0 停用 / 1 启用）");
        }
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) body.get("device_keys");

        int affected = deviceService.updateStatus(keys, Integer.parseInt(String.valueOf(status)));
        return Result.ok(Map.of("affected", affected));
    }

    /**
     * 评审 4.7：注册批次列表（从设备表推导，不再依赖前端 localStorage）。
     *
     * <p>返回 {@code {batches, ungrouped, deviceTotal}} —— ungrouped 是"不按批次命名规律
     * 注册的设备数"，如实给出以免用户看到台数对不上时无从解释。
     */
    @GetMapping("/devices/batches")
    public Result<Map<String, Object>> batches(@RequestParam(defaultValue = "50") int limit) {
        return Result.ok(deviceService.listBatches(limit));
    }

    /** 3.3 批量删除设备：body {"device_keys": ["dev_xxx", ...]} */
    @DeleteMapping("/devices")
    public Result<Map<String, Object>> deleteBatch(@RequestBody Map<String, List<String>> body) {
        List<String> keys = body.getOrDefault("device_keys", List.of());
        int deleted = deviceService.deleteDevices(keys);
        return Result.ok(Map.of("deleted", deleted));
    }

    /** 3.3 详情页「操作日志」：设备上下线/操作记录分页 */
    @GetMapping("/devices/{deviceKey}/logs")
    public Result<Map<String, Object>> deviceLogs(@PathVariable String deviceKey,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> result = deviceService.listDeviceLogs(deviceKey, page, size);
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