package com.iot.platform.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.dto.TelemetryRequest;
import com.iot.platform.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E2 设备实时数据推送端点（替代前端 5s 轮询）。
 *
 * <h3>协议（契约 §9）</h3>
 * <ul>
 *   <li>握手：{@code ws://host:8080/ws/telemetry?token=<JWT>&device_key=<可选>}
 *       —— token 非法即以策略违规码关闭；</li>
 *   <li>服务端 → 客户端：{@code {"type":"telemetry","device_key":..,"ts":..,"properties":{..}}}、{@code {"type":"pong"}}；</li>
 *   <li>客户端 → 服务端：{@code {"type":"ping"}} 心跳。</li>
 * </ul>
 *
 * <p>订阅粒度：带 {@code device_key} 只收该设备，不带则收全部。
 *
 * <h3>两个必须知道的约束</h3>
 * <ol>
 *   <li><b>会话表是实例内存态</b> → 本实现<b>仅适用于单实例部署</b>。多副本需改用 Redis Pub/Sub
 *       或 STOMP broker 中转，已记入 V2-G（平台多副本）的前置条件。</li>
 *   <li><b>{@link WebSocketSession} 非线程安全</b> → 并发 {@code sendMessage} 会抛
 *       {@code IllegalStateException(TEXT_PARTIAL_WRITING)}。多个 Kafka 消费线程同时广播时必然触发，
 *       故发送处必须对 session 加锁。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    /** 会话表：sessionId → session（单实例内存态，见类注释约束 1） */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    // ===== 连接生命周期 =====

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String token = queryParam(session, "token");
        String deviceKey = queryParam(session, "device_key");

        if (!isValidToken(token)) {
            log.warn("WS 握手鉴权失败，关闭连接: sessionId={}", session.getId());
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("token invalid or expired"));
            return;
        }

        session.getAttributes().put("device_key", deviceKey == null ? "" : deviceKey);
        sessions.put(session.getId(), session);
        log.info("WS 已连接: sessionId={}, 订阅={}, 当前会话数={}",
                session.getId(),
                deviceKey == null || deviceKey.isEmpty() ? "全部设备" : deviceKey,
                sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WS 已断开: sessionId={}, 原因={}, 剩余会话数={}", session.getId(), status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session.getId());
        log.warn("WS 传输异常，移除会话: sessionId={}, err={}", session.getId(), exception.getMessage());
    }

    // ===== 客户端消息（心跳）=====

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if ("ping".equals(node.path("type").asText())) {
                sendMap(session, Map.of("type", "pong"));
            }
        } catch (Exception e) {
            log.debug("WS 收到非 JSON 消息，忽略: {}", message.getPayload());
        }
    }

    // ===== 对外广播（由遥测消费者调用）=====

    /**
     * 把一条<b>已成功落库</b>的遥测推给订阅了该设备（或订阅全部）的会话。
     *
     * <p>调用时机：{@code TelemetryServiceImpl.processSync} 成功之后 —— 先落库再推送，
     * 避免前端收到尚未持久化的数据。
     */
    public void broadcastTelemetry(TelemetryRequest req) {
        if (sessions.isEmpty()) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "telemetry");
        payload.put("device_key", req.getDeviceKey());
        payload.put("ts", req.getTs());
        payload.put("properties", req.getProperties());

        broadcast(req.getDeviceKey(), payload);
    }

    /** 设备在线状态变化（收到上报即视为在线） */
    public void broadcastStatus(String deviceKey, int onlineStatus) {
        if (sessions.isEmpty()) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "status");
        payload.put("device_key", deviceKey);
        payload.put("online_status", onlineStatus);

        broadcast(deviceKey, payload);
    }

    // ===== 内部工具 =====

    private void broadcast(String deviceKey, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("WS 报文序列化失败: {}", e.getMessage());
            return;
        }
        TextMessage message = new TextMessage(json);

        for (WebSocketSession session : sessions.values()) {
            if (!session.isOpen()) {
                sessions.remove(session.getId());
                continue;
            }
            String subscribed = String.valueOf(session.getAttributes().getOrDefault("device_key", ""));
            // 空 = 订阅全部；非空则只推该设备
            if (!subscribed.isEmpty() && !subscribed.equals(deviceKey)) continue;
            sendRaw(session, message);
        }
    }

    private void sendMap(WebSocketSession session, Map<String, Object> payload) {
        try {
            sendRaw(session, new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception e) {
            log.warn("WS 发送失败: {}", e.getMessage());
        }
    }

    /** 发送原文 —— <b>必须对 session 加锁</b>，见类注释约束 2 */
    private void sendRaw(WebSocketSession session, TextMessage message) {
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (Exception e) {
                log.warn("WS 发送失败，移除会话: sessionId={}, err={}", session.getId(), e.getMessage());
                sessions.remove(session.getId());
            }
        }
    }

    private boolean isValidToken(String token) {
        if (token == null || token.isEmpty()) return false;
        try {
            jwtUtil.parse(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String queryParam(WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && name.equals(pair.substring(0, i))) {
                return pair.substring(i + 1);
            }
        }
        return null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 会话已不可用，关闭失败无需处理
        }
    }
}
