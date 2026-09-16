package com.aslp.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 JWT 签发测试：不启动 Spring 上下文，直接用 JJWT 回读校验。
 *
 * <p>关键契约：网关侧的 {@code NimbusReactiveJwtDecoder} 固定使用 <b>HS384</b> 对称密钥
 * （见 {@code VpnSecurityConfig#jwtDecoder}）。因此这里显式断言签发算法为 HS384 ——
 * 一旦密钥长度或算法变化导致签发 HS512 等，网关会直接拒绝令牌，属跨服务契约，必须锁死。
 */
class JwtTokenServiceTest {

    /**
     * 测试密钥：长度 54 字节。
     * JJWT 会按密钥长度自动选择 HMAC 算法：48–63 字节 → HS384，恰好与网关侧一致。
     * 该值仅为测试用途，不含任何真实密钥。
     */
    private static final String TEST_SECRET = "aslp-unit-test-secret-0123456789-0123456789-0123456789";

    private static final String ISSUER = "aslp-auth-service";
    private static final long TTL_MINUTES = 120L;

    private final JwtTokenService service = new JwtTokenService(TEST_SECRET, TTL_MINUTES, ISSUER);

    private Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token);
    }

    @Test
    @DisplayName("签发的令牌可用同一密钥验签，且声明完整（sub/roles/scope/iss）")
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
    @DisplayName("算法必须是 HS384：网关侧解码器固定 HS384，算法不符会被拒")
    void tokenIsSignedWithHs384() {
        String token = service.issueToken("admin", List.of("ADMIN"));

        assertEquals("HS384", parse(token).getHeader().getAlgorithm());
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
