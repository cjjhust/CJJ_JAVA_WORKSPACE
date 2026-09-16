package com.aslp.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * M1 — JWT 令牌签发（RBAC）。
 *
 * <p>网关侧的 VPN 安全接入（M4）会校验本服务签发的令牌。
 * 生产环境请通过 {@code ASLP_JWT_SECRET} 环境变量注入密钥。
 */
@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final long ttlMinutes;
    private final String issuer;

    public JwtTokenService(
            @Value("${aslp.jwt.secret}") String secret,
            @Value("${aslp.jwt.ttl-minutes:120}") long ttlMinutes,
            @Value("${aslp.jwt.issuer:aslp-auth-service}") String issuer) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
        this.issuer = issuer;
    }

    /**
     * 签发访问令牌。
     *
     * @param username 用户名
     * @param roles    角色（不含 {@code ROLE_} 前缀，网关侧通过 {@code scope}/{@code roles} 声明映射）
     */
    public String issueToken(String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(username)
                .claim("roles", roles)
                .claim("scope", String.join(" ", roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }
}
