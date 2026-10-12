package com.iot.platform.service;

import com.iot.platform.dto.TelemetryRequest;

public interface TelemetryService {
    void process(TelemetryRequest request);

    /**
     * D1：Kafka 消费者线程内执行的同步写路径。
     *
     * <p>D2-2 起语义变更 —— <b>成功正常返回；失败一律抛异常</b>，交由 {@code @RetryableTopic} 分级处理：
     * <ul>
     *   <li>{@link com.iot.platform.common.PermanentTelemetryException}：永久失败，被 exclude 后跳过重试直达 DLT；</li>
     *   <li>其他 {@link RuntimeException}：瞬时失败，退避重试，耗尽后进 DLT。</li>
     * </ul>
     *
     * <p><b>为什么不再是 boolean</b>：旧实现失败时返回 false 让调用方"跳过 ack"，
     * 而 {@code AckMode.MANUAL} 的 ack 提交的是分区位点 —— 后一条成功消息一 ack 就会把这条失败记录
     * 越过提交，既不重试也无死信，遥测静默丢失（BUG-21 实验证据：partition offset 2 被直接跳到 4）。
     */
    void processSync(TelemetryRequest request);
}