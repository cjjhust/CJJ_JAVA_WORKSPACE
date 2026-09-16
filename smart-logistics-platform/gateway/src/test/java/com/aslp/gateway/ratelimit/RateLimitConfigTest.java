package com.aslp.gateway.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-2 限流 key 解析器单元测试。
 *
 * <p>不使用 Mockito 扩展（保持非严格模式），因为不同用例触发的桩不同。
 */
class RateLimitConfigTest {

    private final RateLimitConfig config = new RateLimitConfig();

    private static Jwt jwt(String subject, String preferredUsername) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS384")
                .subject(subject);
        if (preferredUsername != null) {
            builder.claim("preferred_username", preferredUsername);
        }
        return builder.build();
    }

    private static ServerWebExchange exchange(Authentication authentication, String forwardedFor) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (forwardedFor != null) {
            headers.add("X-Forwarded-For", forwardedFor);
        }
        when(request.getHeaders()).thenReturn(headers);
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getPrincipal()).thenReturn(authentication == null ? Mono.empty() : Mono.just(authentication));
        return exchange;
    }

    @Test
    @DisplayName("用户维度：已登录时按 JWT 用户名分流，并带 user: 前缀")
    void userKeyResolver_prefersJwtUsername() {
        ServerWebExchange exchange = exchange(new JwtAuthenticationToken(jwt("auth-service", "admin")), null);

        assertEquals("user:admin", config.userKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("用户维度：JWT 无 preferred_username 时回落 sub")
    void userKeyResolver_fallsBackToSubject() {
        ServerWebExchange exchange = exchange(new JwtAuthenticationToken(jwt("svc-account", null)), null);

        assertEquals("user:svc-account", config.userKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("用户维度：匿名请求回落 IP，且绝不为空（空 key 会被框架 403 拒绝）")
    void userKeyResolver_fallsBackToIpWhenAnonymous() {
        ServerWebExchange exchange = exchange(null, "203.0.113.7");

        String key = config.userKeyResolver().resolve(exchange).block();

        assertEquals("user:203.0.113.7", key);
    }

    @Test
    @DisplayName("两个维度的 key 天然隔离：user: 与 ip: 前缀不会落在同一个令牌桶")
    void userAndIpKeysAreNamespaced() {
        ServerWebExchange authenticated = exchange(new JwtAuthenticationToken(jwt("auth-service", "admin")), null);
        ServerWebExchange anonymous = exchange(null, "203.0.113.7");

        assertEquals("user:admin", config.userKeyResolver().resolve(authenticated).block());
        assertEquals("ip:203.0.113.7", config.ipKeyResolver().resolve(anonymous).block());
    }

    @Test
    @DisplayName("IP 维度：带 ip: 前缀，优先取 X-Forwarded-For 首跳地址")
    void ipKeyResolver_prefersForwardedForFirstHop() {
        ServerWebExchange exchange = exchange(null, "203.0.113.7, 10.0.0.1");

        assertEquals("ip:203.0.113.7", config.ipKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("IP 维度：无代理头时取 TCP 远端地址")
    void ipKeyResolver_usesRemoteAddressWhenNoHeader() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("198.51.100.9", 51234));
        when(exchange.getRequest()).thenReturn(request);

        assertEquals("ip:198.51.100.9", config.ipKeyResolver().resolve(exchange).block());
    }
}
