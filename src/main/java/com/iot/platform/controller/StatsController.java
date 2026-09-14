package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.service.StatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * E6 控制台统计接口（仪表盘「物联概览」数据源）。
 *
 * <p>路径在 {@code /api/v1/**} 下，受 {@code JwtInterceptor} 保护（控制台登录后携带 token 调用）。
 * 接口契约见 {@code docs/02_设计契约/API契约.md} §7。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    /** 概览统计：product / device / message / alarm 四组数字 */
    @GetMapping("/stats/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(statsService.overview());
    }

    /**
     * 消息趋势。
     *
     * @param granularity {@code day}（默认，近 30 天）或 {@code month}（近 12 个月）
     * @param range       自定义跨度：day 为天数、month 为月数（留空用默认值）
     */
    @GetMapping("/stats/message-trend")
    public Result<Map<String, Object>> messageTrend(
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) Integer range) {
        return Result.ok(statsService.messageTrend(granularity, range));
    }

    /** 告警列表（E6 阶段返回空列表：告警数据链路依赖 E3，接口与结构已定型） */
    @GetMapping("/alerts")
    public Result<Map<String, Object>> alerts(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(statsService.alerts(page, size));
    }
}
