package com.iot.platform.service;

import com.iot.platform.dto.TelemetryRequest;

public interface TelemetryService {
    void process(TelemetryRequest request);

    /**
     * D1：Kafka 消费者线程内执行的同步写路径。
     * 落库成功返回 true（调用方据此提交 offset，实现「消费成功才 ack」）。
     */
    boolean processSync(TelemetryRequest request);
}