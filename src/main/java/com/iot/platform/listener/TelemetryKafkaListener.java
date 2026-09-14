package com.iot.platform.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.PermanentTelemetryException;
import com.iot.platform.config.KafkaConfig;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.service.TelemetryService;
import com.iot.platform.ws.TelemetryWebSocketHandler;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * D2-2 消费端背压：失败不再"跳过 ack"，而是<b>抛异常</b>交由 {@code @RetryableTopic} 分级处理。
 *
 * <h3>为什么必须改（BUG-21 实验证据）</h3>
 * {@code AckMode.MANUAL} 下 {@code acknowledge()} 提交的是<b>分区位点</b>，而非"撤回单条"。
 * 失败记录不 ack 时，下一条成功记录的 ack 会把它一并越过 —— 既不重试、也无死信，遥测静默丢失
 * （实测：partition 2 的 offset 2 被直接跳到 4，失败计数全程停在 1.0）。
 *
 * <h3>分级策略</h3>
 * <ul>
 *   <li><b>瞬时失败</b>（DB 断开/超时/死锁）→ 非阻塞重试 {@code iot.telemetry-retry-0/1/2}，退避 1→2→4s；</li>
 *   <li><b>永久失败</b>（{@link PermanentTelemetryException}）→ {@code exclude}，跳过重试直达 DLT；</li>
 *   <li><b>重试耗尽</b> → {@code iot.telemetry-dlt}，计数 {@code iot_telemetry_dlt_total}，可人工重放。</li>
 * </ul>
 *
 * <p>失败消息被搬到旁路 topic 重试，主分区只推进已成功（或已转交）的记录，
 * 因此<b>从结构上</b>消除了 head-of-line 阻塞与"越过"。
 *
 * <p><b>可靠性最终由两半合围</b>：本类保证"不丢"（至少一次投递 + 死信隔离），
 * D2-1 的唯一键 {@code uk_device_prop_val_ts} 保证"不重"（重试天然幂等）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryKafkaListener {

    private final TelemetryService telemetryService;
    private final TelemetryWebSocketHandler wsHandler;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @RetryableTopic(
            attempts = "4",                                        // 1 次主投 + 3 次重试
            backoff = @Backoff(delay = 1000L, multiplier = 2.0, maxDelay = 10000L), // 1→2→4s
            autoCreateTopics = "true",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, // -retry-0/1/2
            exclude = {PermanentTelemetryException.class},         // 永久失败不浪费重试
            dltTopicSuffix = "-dlt"
    )
    @KafkaListener(topics = KafkaConfig.TOPIC, groupId = KafkaConfig.CONSUMER_GROUP)
    public void onMessage(String payload) {
        TelemetryRequest request = parse(payload);
        telemetryService.processSync(request);
        // E2：落库成功后推送给在线控制台。
        // 顺序很重要 —— 先持久化再推送，前端收到的每一条都是已入库的数据。
        wsHandler.broadcastTelemetry(request);
    }

    /** 反序列化失败同样是永久失败（脏报文不会自愈），直接送 DLT，不占重试阶梯。 */
    private TelemetryRequest parse(String payload) {
        try {
            return objectMapper.readValue(payload, TelemetryRequest.class);
        } catch (Exception e) {
            meterRegistry.counter("iot_telemetry_process_failures_total", "result", "permanent").increment();
            log.warn("telemetry payload 反序列化失败，转 DLT: {}", payload);
            throw new PermanentTelemetryException("telemetry payload 反序列化失败: " + payload, e);
        }
    }

    /** 死信落点：重试耗尽的遥测在此集中记录并计数，便于排查与人工重放。 */
    @DltHandler
    public void onDlt(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("telemetry 最终失败，已隔离至 DLT [{}]，原文: {}", topic, payload);
        meterRegistry.counter("iot_telemetry_dlt_total").increment();
    }
}
