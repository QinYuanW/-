package com.iot.platform.client;

import com.iot.platform.config.EmqxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmqxClient {

    private final EmqxProperties properties;

    private RestClient restClient() {
        // 强制 HTTP/1.1：JDK HttpClient 默认 HTTP/2 会被 EMQX 拒绝（EOF reached while reading）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder()
                .baseUrl(properties.getApiUrl())
                .requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth(properties.getUsername(), properties.getPassword()))
                .build();
    }

    /**
     * 通过 EMQX REST API 发布消息（topic 按设备约定，payload 为 JSON 字符串）
     */
    public boolean publish(String topic, String payload) {
        try {
            restClient().post()
                    .uri("/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "topic", topic,
                            "payload", payload,
                            "qos", 1,
                            "retain", false))
                    .retrieve()
                    .toBodilessEntity();
            log.info("emqx publish ok: topic={}", topic);
            return true;
        } catch (Exception e) {
            log.error("emqx publish failed: topic={}, err={}", topic, e.getMessage());
            return false;
        }
    }
}
