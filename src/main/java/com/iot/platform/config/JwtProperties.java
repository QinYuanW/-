package com.iot.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** HS256 要求至少 32 字节 */
    private String secret = "iot-platform-mvp-jwt-secret-key-please-change-0123456789";

    /** 过期时间（秒），默认 2 小时 */
    private long expire = 7200;
}
