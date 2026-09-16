package com.iot.platform.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.PermanentTelemetryException;
import com.iot.platform.config.KafkaConfig;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.service.RuleEngineService;
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
    private final RuleEngineService ruleEngineService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** 重试策略
     * `@RetryableTopic` = 让消费失败的消息 "换主题重试、退避重投、最后进死信"
     */
    @RetryableTopic(
            attempts = "4",                                        // 1 次主投 + 3 次重试
            backoff = @Backoff(delay = 1000L, multiplier = 2.0, maxDelay = 10000L), // 1→2→4s
            autoCreateTopics = "true",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE, // -retry-0/1/2
            exclude = {PermanentTelemetryException.class},         // 这类异常不重试，直接进 DLT，（永久失败异常不浪费重试）
            dltTopicSuffix = "-dlt"
    )
    /** 消费遥测消息
     * **`@KafkaListener`** 是 Spring for Apache Kafka 的注解，
     * 作用：把下面的方法注册成一个**消息监听器**——
     * 应用启动后框架自动创建监听容器，订阅指定主题，每来一条消息就调用一次该方法。
     * */
    /**
     * @KafkaListener 做的事
     * Spring Kafka 在项目启动时扫描所有带有 @KafkaListener 注解的方法，然后：
     *
     * 自动创建消费者 — 为这个注解创建一个 Kafka Consumer 客户端
     * 订阅主题 — 订阅 KafkaConfig.TOPIC（即 iot.telemetry）
     * 轮询拉取 — 启动一个后台线程，不断调用 consumer.poll() 从 Kafka 拉取消息
     * 消息分发 — 每拉取到一批消息，逐个调用 onMessage(payload)，把消息的 value 作为 payload 参数传进去
     * */
    @KafkaListener(topics = KafkaConfig.TOPIC, groupId = KafkaConfig.CONSUMER_GROUP)
    public void onMessage(String payload) {
        TelemetryRequest request = parse(payload);// 反序列化遥测请求
        telemetryService.processSync(request);

        // E2：落库成功后推送给在线控制台。
        // 顺序很重要 —— 先持久化再推送，前端收到的每一条都是已入库的数据。
        wsHandler.broadcastTelemetry(request);

        // E3：规则引擎评估（内部全量吞异常）。放在最后 —— 规则是旁路，
        // 它出问题不该影响"入库 + 推送"这两件正事，也不该让消息进重试阶梯。
        ruleEngineService.evaluate(request);
    }

    /** 反序列化失败同样是永久失败（脏报文不会自愈），直接送 DLT，不占重试阶梯。 */
    private TelemetryRequest parse(String payload) {
        try {
            return objectMapper.readValue(payload, TelemetryRequest.class);
        } catch (Exception e) {
            meterRegistry.counter("iot_telemetry_process_failures_total", "result", "permanent").increment();
            log.warn("telemetry payload 反序列化失败，转 DLT: {}", payload);// 记录失败日志
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
