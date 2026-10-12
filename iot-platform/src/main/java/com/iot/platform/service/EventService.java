package com.iot.platform.service;

import com.iot.platform.dto.EventRequest;

import java.util.Map;

/**
 * E3 设备事件服务：接收（EMQX webhook）与查询。
 */
public interface EventService {

    /**
     * 接收一条设备事件并落库。
     *
     * <p>契约 §8.1：立即返回 200，落库失败不应让 EMQX 无限重试（事件是"发生过"的事实，
     * 丢一条比拖垮入口更可接受）。因此实现内部吞异常。
     */
    void receive(EventRequest request);

    /**
     * 查询某设备的事件分页（契约 §8.2）。
     *
     * <p>{@code eventName} / {@code type} 由后端按产品 TSL 回填 —— 前端不查 TSL，
     * 且历史数据不随 TSL 改名而变。
     */
    Map<String, Object> page(String deviceKey, Integer page, Integer size, String eventKey);
}
