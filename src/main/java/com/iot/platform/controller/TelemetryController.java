package com.iot.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.Result;
import com.iot.platform.config.KafkaConfig;
import com.iot.platform.dto.TelemetryRequest;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TelemetryController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * D2-FIX BUG-18：改异步发送，webhook 立即返回 200，消除 EMQX 侧积压导致的 request_ttl 过期丢消息。
     * 耐久性由 Kafka producer 后台批量 + acks=all 保证；发送失败仅计数不谎报（D2 唯一约束兜底重复）。
     */
    @PostMapping("/telemetry")
    public Result<Void> receive(@RequestBody TelemetryRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            kafkaTemplate.send(KafkaConfig.TOPIC, request.getDeviceKey(), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("kafka async publish failed: device_key={}", request.getDeviceKey(), ex);
                            meterRegistry.counter("iot_telemetry_kafka_publish_failures_total").increment();
                        }
                    });
            return Result.ok();
        } catch (Exception e) {
            log.error("telemetry serialize failed: device_key={}", request.getDeviceKey(), e);
            return Result.fail(5000, "服务内部错误");
        }
    }
}