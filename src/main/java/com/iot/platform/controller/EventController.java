package com.iot.platform.controller;

import com.iot.platform.common.Result;
import com.iot.platform.dto.EventRequest;
import com.iot.platform.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * E3 设备事件接口。
 *
 * <p>{@code POST /api/v1/events} 是 EMQX 规则引擎的 webhook 回调 —— 匹配 MQTT 主题
 * {@code up/+/event}，<b>已加入 JWT 拦截器放行清单</b>（EMQX 无法携带 token）。
 * {@code GET /api/v1/devices/{deviceKey}/events} 供控制台「事件记录」tab 查询。
 * 契约见 {@code docs/02_设计契约/API契约.md} §8。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /** 事件上报接收（EMQX webhook） */
    @PostMapping("/events")
    public Result<Void> receive(@RequestBody EventRequest request) {
        eventService.receive(request);
        return Result.ok();
    }

    /** 事件分页查询（控制台「事件记录」tab） */
    @GetMapping("/devices/{deviceKey}/events")
    public Result<Map<String, Object>> page(@PathVariable String deviceKey,
                                            @RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "20") Integer size,
                                            @RequestParam(required = false) String eventKey) {
        return Result.ok(eventService.page(deviceKey, page, size, eventKey));
    }
}
