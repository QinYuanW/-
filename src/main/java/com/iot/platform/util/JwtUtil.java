package com.iot.platform.util;

import com.iot.platform.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties jwtProperties;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

        /** 生成 JWT 生成 token */
           public String generate(String username, Long userId, Integer role) {
        return generate(username, userId, role, jwtProperties.getExpire());
    }

    /**
     * 生成 token 并指定有效期（秒）。
     *
     * <p>评审 2.6「记住我」用这个重载签发更长有效期的 token；
     * 保留原方法是为了不动其它调用方（默认 2 小时）。
     */
    public String generate(String username, Long userId, Integer role, long expireSeconds) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireSeconds * 1000L);
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key())
                .compact();
    }
        /** 解析 JWT token */
        public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key()) // 用配置的 secret 验签
                .build()
                .parseSignedClaims(token) // 解析并校验签名、过期时间等
                .getPayload();  // 取出 Payload（uid/role/sub 等声明）
    }
}
