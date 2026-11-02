package com.aslp.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 JWT 签发测试：不启动 Spring 上下文，直接用 JJWT 回读校验（P0-2 起为 RS256）。
 *
 * <p>关键契约：网关侧的 {@code NimbusReactiveJwtDecoder.withJwkSetUri} 从
 * {@code /api/auth/jwks.json} 拉取公钥验签，固定期望 RS256。这里显式断言：
 * 签发算法为 RS256、JWKS 三要素（kty/n/e）与签发公钥一致 ——
 * 算法或公钥变更会导致网关直接拒绝令牌，属跨服务契约，必须锁死。
 */
class JwtTokenServiceTest {

    private static final String ISSUER = "aslp-auth-service";
    private static final long TTL_MINUTES = 120L;

    private final JwtTokenService service = new JwtTokenService(TTL_MINUTES, ISSUER);

    private Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(service.getPublicKey())
                .build()
                .parseSignedClaims(token);
    }

    @Test
    @DisplayName("签发的令牌可用发布出的公钥验签，且声明完整（sub/roles/scope/iss）")
    void issuedTokenCarriesExpectedClaims() {
        String token = service.issueToken("admin", List.of("ADMIN", "USER"));

        Claims claims = parse(token).getPayload();

        assertEquals("admin", claims.getSubject());
        assertEquals(ISSUER, claims.getIssuer());
        assertEquals(List.of("ADMIN", "USER"), claims.get("roles", List.class));
        assertEquals("ADMIN USER", claims.get("scope"),
                "scope 用空格拼接，兼容 Spring Security 的 SCOPE_* 权限推导");
    }

    @Test
    @DisplayName("算法必须是 RS256：网关经 JWKS 以 RSA 公钥验签，算法不符会被拒")
    void tokenIsSignedWithRs256() {
        String token = service.issueToken("admin", List.of("ADMIN"));

        assertEquals("RS256", parse(token).getHeader().getAlgorithm());
    }

    @Test
    @DisplayName("JWKS 三要素与签发公钥一致（n/e 可还原出同一个公钥）")
    void jwksElementsMatchSigningKey() {
        String n = service.getPublicKeyModulusBase64Url();
        String e = service.getPublicKeyExponentBase64Url();

        RSAPublicKey key = service.getPublicKey();
        assertEquals("RSA", key.getAlgorithm());
        assertEquals(2048, key.getModulus().bitLength());
        // n/e 是 Base64URL（无 padding）；e 恒为 65537 → "AQAB"
        assertEquals("AQAB", e);
        // 用 JWKS 里的 n/e 重建公钥并验签，证明端到端可行
        byte[] modulus = java.util.Base64.getUrlDecoder().decode(n);
        java.math.BigInteger m = new java.math.BigInteger(1, modulus);
        java.math.BigInteger exp = new java.math.BigInteger(1, java.util.Base64.getUrlDecoder().decode(e));
        java.security.KeyFactory factory;
        try {
            factory = java.security.KeyFactory.getInstance("RSA");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        RSAPublicKey rebuilt;
        try {
            rebuilt = (RSAPublicKey) factory.generatePublic(new java.security.spec.RSAPublicKeySpec(m, exp));
        } catch (java.security.spec.InvalidKeySpecException ex) {
            throw new IllegalStateException(ex);
        }
        String token = service.issueToken("admin", List.of("USER"));
        Claims claims = Jwts.parser().verifyWith(rebuilt).build().parseSignedClaims(token).getPayload();
        assertEquals("admin", claims.getSubject());
    }

    @Test
    @DisplayName("有效期为配置的 TTL（分钟）")
    void expirationMatchesConfiguredTtl() {
        Claims claims = parse(service.issueToken("admin", List.of("USER"))).getPayload();

        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
        assertEquals(TTL_MINUTES,
                Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant()).toMinutes());
        assertEquals(TTL_MINUTES, service.getTtlMinutes());
    }

    @Test
    @DisplayName("空角色列表：不抛异常，roles 为空、scope 为空串")
    void emptyRolesAreSupported() {
        Claims claims = parse(service.issueToken("viewer", List.of())).getPayload();

        assertEquals(List.of(), claims.get("roles", List.class));
        assertEquals("", claims.get("scope"));
    }
}
