package com.iot.platform.health;

import com.iot.platform.config.EmqxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * EMQX 健康检查：调用 /api/v5/status（公开端点）。
 * DB / Redis 由 Actuator 内置的 DataSource / Redis 指示器自动检测。
 */
@Component
@RequiredArgsConstructor
public class EMQXHealthIndicator implements HealthIndicator {

    private final EmqxProperties properties;

    private RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        return RestClient.builder()
                .baseUrl(properties.getApiUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public Health health() {
        try {
            String body = restClient().get().uri("/status").retrieve().body(String.class);
            return Health.up().withDetail("status", body).build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }
}