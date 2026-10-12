package com.iot.platform.config;

import com.iot.platform.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 拦截器。
 * 保护控制台业务接口 /api/v1/**，放行登录与 EMQX 设备侧回调接口。
 */
@Configuration // 配置类
@RequiredArgsConstructor // 构造函数注入
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;// 注入 JWT 拦截器

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor) // 注册 JWT 拦截器
                .addPathPatterns("/api/v1/**") // 保护所有 API 路由
                .excludePathPatterns(            // 放行登录与 EMQX 设备侧回调接口
                        "/api/v1/login",
                        "/api/v1/auth",
                        "/api/v1/telemetry",
                        "/api/v1/commands/ack",
                        "/api/v1/events"        // E3 事件上报（EMQX 规则引擎回调，契约 §8.1）
                );
    }
}
