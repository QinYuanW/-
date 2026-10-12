package com.iot.platform.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * E3 事件上报请求（EMQX 规则引擎 → POST /api/v1/events）。
 *
 * <p><b>设计说明</b>：契约 §8.1 原定平铺 {@code event_key} / {@code params}，但 EMQX body 模板里
 * 若事件不带 params，{@code ${params}} 会被渲染成字面 {@code undefined} 导致整个 JSON 解析失败。
 * 因此改为<b>设备 payload 整体透传</b>（EMQX 只当搬运工，字段解析由后端做），
 * {@code event_key} / {@code params} 从 {@link #payload} 里取 —— 契约已同步更新。
 */
@Data
public class EventRequest {

    @JsonProperty("device_key")
    private String deviceKey;

    /** 事件发生时间（毫秒时间戳，EMQX 规则取 timestamp） */
    private Long ts;

    /**
     * 设备上报的事件原文。约定形如：
     * {@code {"event_key":"high_temp_alarm","params":{"temperature":58.2}}}
     *
     * <p><b>类型是 {@code Object} 而非 Map</b>：EMQX 规则输出的 {@code payload} 可能以
     * JSON 对象或「转义后的 JSON 字符串」两种形态渲染（实测 rule_test 输出为字符串），
     * 若声明为 Map，字符串形态会直接反序列化失败。后端用 {@code parsePayloadFlexible} 兼容两种。
     */
    private Object payload;
}
