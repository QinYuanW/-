package com.iot.platform.config;

import com.iot.platform.ws.TelemetryWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * E2 WebSocket 配置：注册设备实时数据推送端点。
 *
 * <p>连接地址：{@code ws://localhost:8080/ws/telemetry?token=<JWT>&device_key=<device_key>}
 * —— 浏览器的 WebSocket API 无法自定义请求头，因此凭据走查询参数。
 *
 * <p>注意路径<b>不在 {@code /api/v1}</b> 下，故不受 {@code JwtInterceptor} 拦截，
 * 鉴权由 {@link TelemetryWebSocketHandler} 在握手阶段自行完成。
 * 契约见 {@code docs/02_设计契约/API契约.md} §9。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final TelemetryWebSocketHandler telemetryWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 本地开发放开来源限制；对外部署前应收紧为控制台域名（并入 V2-F 安全加固清单）
        registry.addHandler(telemetryWebSocketHandler, "/ws/telemetry")
                .setAllowedOriginPatterns("*");
    }
}
