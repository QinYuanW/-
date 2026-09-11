package com.iot.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.Result;
import com.iot.platform.config.KafkaConfig;
import com.iot.platform.dto.TelemetryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TelemetryController {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * D1：EMQX Webhook 回调在此把遥测写入 Kafka（acks=all，broker 确认持久化后才返回 200）。
     * 相比原「@Async 直接入库」：确认语义从 HTTP 200 下沉到 Kafka broker ack（C5 可靠性改进）。
     */
    @PostMapping("/telemetry")
    public Result<Void> receive(@RequestBody TelemetryRequest request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            // 同步等待 producer 发送完成（acks=all，等待 broker 确认）；key = device_key 保证同设备顺序
            kafkaTemplate.send(KafkaConfig.TOPIC, request.getDeviceKey(), json)
                    .get(3, TimeUnit.SECONDS);
            return Result.ok();
        } catch (Exception e) {
            log.error("telemetry publish to kafka failed: device_key={}", request.getDeviceKey(), e);
            // 语义：未确认持久化 → 非 200 → EMQX 会按 max_retries 重试（尽力投递层仍生效）
            return Result.fail(5000, "服务内部错误");
        }
    }
}