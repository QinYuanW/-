package com.iot.platform.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.config.KafkaConfig;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * D1 Kafka 消费者组：消费 iot.telemetry，同步落库成功后手动 ack（offset 提交）。
 * - 手动 ack = 「消费成功才确认」：后端重启后未 ack 的在途消息由 offset 续消费，不再丢在途（C5-S2 根因缓解）
 * - 落库失败不 ack，交由后续（D2）重试/背压策略处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryKafkaListener {

    private final TelemetryService telemetryService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaConfig.TOPIC, groupId = KafkaConfig.CONSUMER_GROUP)
    public void onMessage(String payload, Acknowledgment acknowledgment) {
        try {
            TelemetryRequest request = objectMapper.readValue(payload, TelemetryRequest.class);
            boolean ok = telemetryService.processSync(request);
            if (ok) {
                // 已落库，提交 offset
                acknowledgment.acknowledge();
            } else {
                log.warn("telemetry persist failed (unknown device?), skip ack: {}", payload);
            }
        } catch (Exception e) {
            log.error("kafka consume error, skip ack", e);
        }
    }
}