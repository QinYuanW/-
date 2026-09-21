package com.iot.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component // 组件注解，将类注册为 Spring Bean
@ConfigurationProperties(prefix = "jwt") // 配置属性前缀，用于绑定 application.yml 中的 jwt 配置
public class JwtProperties {

    /** HS256 要求至少 32 字节 */
    private String secret = "iot-platform-mvp-jwt-secret-key-please-change-0123456789";

    /** 过期时间（秒），默认 2 小时 */
    private long expire = 7200;

    /**
     * 「记住我」时的有效期（秒），默认 7 天。
     *
     * <p>评审 2.6：原先只有 2 小时的短 token，用户频繁被踢回登录页，本地开发/演示时尤其烦。
     * 勾选「记住我」即签发这个更长的有效期。
     *
     * <p><b>没有做成"永不过期"</b>：JWT 是无状态的，签发后无法在服务端撤销，
     * 有效期就是唯一的安全边界；7 天是"够用"与"别太长"之间的折中。
     */
    private long rememberExpire = 604800;
}
