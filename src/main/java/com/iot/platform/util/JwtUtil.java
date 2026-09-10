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
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtProperties.getExpire() * 1000L);
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
