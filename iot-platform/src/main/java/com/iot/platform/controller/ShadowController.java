package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.service.ShadowService;
import com.iot.platform.service.ShadowSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * E1 设备影子接口。
 *
 * <p>路径在 {@code /api/v1/**} 下，受 {@code JwtInterceptor} 保护（控制台登录后携带 token 调用）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ShadowController {

    private final ShadowService shadowService;
    private final ShadowSyncService shadowSyncService;

    /** 读取影子：{@code {desired:{}, reported:{}, delta:{}}} */
    @GetMapping("/devices/{deviceKey}/shadow")
    public Result<Map<String, Object>> get(@PathVariable String deviceKey) {
        return Result.ok(shadowService.getShadow(deviceKey));
    }

    /**
     * 写入期望值并尝试下发差异。
     *
     * <p>请求体为「属性名 → 值」的扁平映射，例如 {@code {"switch": true, "temperature": 26}}。
     * 只接受物模型中声明为可写（{@code access != readonly}）的属性。
     * 设备离线时期望值照常保存，差异指令会挂起为 {@code status=0}，设备上线自动补发。
     */
    @PutMapping("/devices/{deviceKey}/shadow/desired")
    public Result<Map<String, Object>> setDesired(@PathVariable String deviceKey,
                                                  @RequestBody Map<String, Object> desired) {
        return Result.ok(shadowSyncService.applyDesired(deviceKey, desired));
    }

    /** 清除某设备的影子（重置期望/上报状态） */
    @DeleteMapping("/devices/{deviceKey}/shadow")
    public Result<Void> clear(@PathVariable String deviceKey) {
        shadowService.clear(deviceKey);
        return Result.ok();
    }
}
