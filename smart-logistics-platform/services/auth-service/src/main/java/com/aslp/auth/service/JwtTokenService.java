package com.aslp.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * M1 — JWT 令牌签发（RBAC）。P0-2：HS384 对称密钥 → RS256 非对称密钥。
 *
 * <p><b>为什么换</b>：HS384 要求网关与 auth-service 共享同一秘密——
 * 网关既能"验"也能"签"，密钥泄漏面翻倍，且轮换必须同时改两处。
 * RS256 下网关只经 JWKS 端点拿到<b>公钥</b>（只能验、不能签），
 * 私钥仅存在于 auth-service，轮换 = 换 kid 发新公钥。
 *
 * <p><b>密钥来源</b>：启动时生成 RSA-2048 密钥对（演示定位）。
 * 生产环境应改为从配置/密钥库加载持久化密钥（多实例间必须一致），
 * 预留 {@code aslp.jwt.rsa-*} 配置点；重启后旧令牌失效在本项目可接受
 * （TTL 120 分钟，会话本来就是短生命周期）。
 *
 * <p>公钥经 {@code GET /api/auth/jwks.json}（JWKS 格式）发布，网关侧
 * {@code NimbusReactiveJwtDecoder.withJwkSetUri} 消费。
 */
@Service
public class JwtTokenService {

    /** JWKS 的 kid：网关按它从 JWKS 里挑公钥。密钥轮换时换这个值。 */
    public static final String KEY_ID = "aslp-auth-rs256-1";

    private final KeyPair keyPair;
    private final long ttlMinutes;
    private final String issuer;

    public JwtTokenService(
            @Value("${aslp.jwt.ttl-minutes:120}") long ttlMinutes,
            @Value("${aslp.jwt.issuer:aslp-auth-service}") String issuer) {
        this.keyPair = generateRsaKeyPair();
        this.ttlMinutes = ttlMinutes;
        this.issuer = issuer;
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            // JVM 必须内置 RSA；到这里说明运行环境根本不是标准 JCE
            throw new IllegalStateException("RSA KeyPairGenerator 不可用", e);
        }
    }

    /**
     * 签发访问令牌（RS256）。
     *
     * @param username 用户名
     * @param roles    角色（不含 {@code ROLE_} 前缀，网关侧通过 {@code roles} 声明映射）
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
                .signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256)
                .compact();
    }

    /** JWKS 的 kty 固定 RSA；e 为公钥指数（RSA 标准几乎恒为 AQAB）。 */
    public String getPublicKeyExponentBase64Url() {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(publicKey.getPublicExponent().toByteArray());
    }

    /** JWKS 的 n 为公钥模数（去掉符号位前导 0，Base64URL）。 */
    public String getPublicKeyModulusBase64Url() {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        byte[] raw = publicKey.getModulus().toByteArray();
        int offset = (raw.length > 0 && raw[0] == 0) ? 1 : 0;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.util.Arrays.copyOfRange(raw, offset, raw.length));
    }

    public RSAPublicKey getPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }
}
