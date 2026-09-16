package com.iot.platform.client;

import com.iot.platform.config.EmqxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * EmqxClient 是连接后端服务和 EMQX 的桥梁，
 * 让 Java 后端通过 HTTP REST API 间接地向设备下发 MQTT 消息
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmqxClient {

    private final EmqxProperties properties;

    private RestClient restClient() {
        // 强制 HTTP/1.1：JDK HttpClient 默认 HTTP/2 会被 EMQX 拒绝（EOF reached while reading）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000); // 连接超时时间，5 秒
        factory.setReadTimeout(10_000); // 读取超时时间，10 秒
        return RestClient.builder()
                .baseUrl(properties.getApiUrl()) // ④ http://localhost:18083/api/v5
                .requestFactory(factory)    // ⑤ 注入自定义的请求工厂
                // ⑥ 默认加上 Basic 认证头
                .defaultHeaders(h -> h.setBasicAuth(properties.getUsername(), properties.getPassword()))
                .build();
    }

    /**
     * 通过 EMQX REST API 发布消息（topic 按设备约定，payload 为 JSON 字符串）
     */
    public boolean publish(String topic, String payload) {
        try {
            restClient().post()                         // ① POST 请求
                    .uri("/publish")                     // ② 路径: /api/v5/publish
                    .contentType(MediaType.APPLICATION_JSON)  // ③ 请求体是 JSON
                    .body(Map.of(                        // ④ 构造请求体
                            "topic", topic,             //    MQTT 主题
                            "payload", payload,         //    消息内容
                            "qos", 1,                   //    QoS 级别
                            "retain", false))           //    是否保留消息
                    .retrieve()                          // ⑤ 发起请求并获取响应
                    .toBodilessEntity();                 // ⑥ 不关心响应体，只验证状态码
            log.info("emqx publish ok: topic={}", topic);
            return true;
        } catch (Exception e) {
            log.error("emqx publish failed: topic={}, err={}", topic, e.getMessage());
            return false;
        }
    }
}
