package com.iot.platform.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iot.platform.common.ErrorCode;
import com.iot.platform.common.Result;
import com.iot.platform.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 控制台业务接口 JWT 鉴权拦截器。
 * 设备侧接口（/auth、/telemetry、/commands/ack）由 EMQX 直接回调，不经过本拦截器。
 */
@Slf4j // 日志注解
@Component // 组件注解
@RequiredArgsConstructor // 构造函数注入
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil; // JWT 工具类
    private final ObjectMapper objectMapper; // JSON 处理工具类

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(response);
        }
        String token = header.substring(7);
        try {
            Claims claims = jwtUtil.parse(token); // 解析 JWT token
            request.setAttribute("uid", claims.get("uid", Long.class)); // 从 Payload 中获取 uid
            request.setAttribute("username", claims.getSubject()); // 从 Payload 中获取 username
            return true;
        } catch (Exception e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            return reject(response);
        }
    }

    private boolean reject(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ErrorCode.AUTH_FAILED, "未登录或登录已过期")));
        return false;
    }
}
