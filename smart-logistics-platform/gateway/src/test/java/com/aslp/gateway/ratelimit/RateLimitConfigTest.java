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

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1-2 限流 key 解析器单元测试（P2-3 起含客户端 IP 防伪造行为）。
 *
 * <p>不使用 Mockito 扩展（保持非严格模式），因为不同用例触发的桩不同。
 */
class RateLimitConfigTest {

    /** 严格模式：不信任任何代理（默认），XFF 一律不采信。 */
    private final RateLimitConfig config = new RateLimitConfig("");

    /** 信任 Docker bridge 网段（docker profile 的口径）。 */
    private final RateLimitConfig configTrustingDockerBridge = new RateLimitConfig("172.16.0.0/12");

    private static Jwt jwt(String subject, String preferredUsername) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS384")
                .subject(subject);
        if (preferredUsername != null) {
            builder.claim("preferred_username", preferredUsername);
        }
        return builder.build();
    }

    private static ServerWebExchange exchange(Authentication authentication, String forwardedFor, String remoteIp) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (forwardedFor != null) {
            headers.add("X-Forwarded-For", forwardedFor);
        }
        when(request.getHeaders()).thenReturn(headers);
        if (remoteIp != null) {
            // IP 字面量：getByName 不做 DNS 解析，返回带真实 Inet4Address 的地址
            try {
                when(request.getRemoteAddress())
                        .thenReturn(new InetSocketAddress(InetAddress.getByName(remoteIp), 0));
            } catch (Exception e) {
                throw new IllegalStateException("测试夹具使用了非法的 IP 字面量：" + remoteIp, e);
            }
        }
        when(exchange.getRequest()).thenReturn(request);
        when(exchange.getPrincipal()).thenReturn(authentication == null ? Mono.empty() : Mono.just(authentication));
        return exchange;
    }

    @Test
    @DisplayName("用户维度：已登录时按 JWT 用户名分流，并带 user: 前缀")
    void userKeyResolver_prefersJwtUsername() {
        ServerWebExchange exchange = exchange(new JwtAuthenticationToken(jwt("auth-service", "admin")), null, "10.0.0.1");

        assertEquals("user:admin", config.userKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("用户维度：JWT 无 preferred_username 时回落 sub")
    void userKeyResolver_fallsBackToSubject() {
        ServerWebExchange exchange = exchange(new JwtAuthenticationToken(jwt("svc-account", null)), null, "10.0.0.1");

        assertEquals("user:svc-account", config.userKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("P2-3 防伪造：严格模式下 XFF 是客户端可控输入，一律不采信，用 TCP 对端地址")
    void strictModeIgnoresForgedForwardedFor() {
        ServerWebExchange exchange = exchange(null, "203.0.113.7, 198.51.100.9", "10.0.0.1");

        assertEquals("ip:10.0.0.1", config.ipKeyResolver().resolve(exchange).block(),
                "未配置可信代理时，伪造的 XFF 必须无效（否则限流可被自己写的头绕过）");
    }

    @Test
    @DisplayName("用户维度：匿名请求回落（防伪造后的）IP，且绝不为空（空 key 会被框架 403 拒绝）")
    void userKeyResolver_fallsBackToIpWhenAnonymous() {
        ServerWebExchange exchange = exchange(null, "203.0.113.7", "10.0.0.1");

        assertEquals("user:10.0.0.1", config.userKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("两个维度的 key 天然隔离：user: 与 ip: 前缀不会落在同一个令牌桶")
    void userAndIpKeysAreNamespaced() {
        ServerWebExchange authenticated = exchange(new JwtAuthenticationToken(jwt("auth-service", "admin")), null, "203.0.113.7");
        ServerWebExchange anonymous = exchange(null, null, "203.0.113.7");

        assertEquals("user:admin", config.userKeyResolver().resolve(authenticated).block());
        assertEquals("ip:203.0.113.7", config.ipKeyResolver().resolve(anonymous).block());
    }

    @Test
    @DisplayName("可信代理链：右向左解析，取可信代理链最深处报告的客户端地址")
    void trustedProxyChainIsResolvedRightToLeft() {
        // XFF: [203.0.113.7, 172.20.0.5]，TCP 对端 172.20.0.10（compose 网段内的网关/代理）
        // 172.20.0.10 可信 → 候选 172.20.0.5；172.20.0.5 可信 → 候选 203.0.113.7
        ServerWebExchange exchange = exchange(null, "203.0.113.7, 172.20.0.5", "172.20.0.10");

        assertEquals("ip:203.0.113.7", configTrustingDockerBridge.ipKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("可信代理链中途断链：遇到不可信发送方立即停止，不再采信更左的地址")
    void resolutionStopsAtUntrustedSender() {
        // XFF: [6.6.6.6, 9.9.9.9, 172.20.0.5]，对端 172.20.0.10（可信）
        // 172.20.0.10 可信 → 候选 172.20.0.5；172.20.0.5 可信 → 候选 9.9.9.9；
        // 9.9.9.9 不可信 → 停止。6.6.6.6 虽然写在更左，但发送方 9.9.9.9 不可信，必须丢弃
        ServerWebExchange exchange = exchange(null, "6.6.6.6, 9.9.9.9, 172.20.0.5", "172.20.0.10");

        assertEquals("ip:9.9.9.9", configTrustingDockerBridge.ipKeyResolver().resolve(exchange).block(),
                "不可信发送方左边的地址都是客户端自己写的，不能采信");
    }

    @Test
    @DisplayName("可信代理配置含非法规则时：非法项被忽略（WARN），合法规则继续生效")
    void invalidProxyRulesAreIgnored() {
        RateLimitConfig mixed = new RateLimitConfig("garbage-host, 999.1.1.1/24, 172.20.0.0/16");
        ServerWebExchange exchange = exchange(null, "203.0.113.7", "172.20.0.10");

        assertEquals("ip:203.0.113.7", mixed.ipKeyResolver().resolve(exchange).block());
    }

    @Test
    @DisplayName("IP 维度：无代理头时取 TCP 远端地址")
    void ipKeyResolver_usesRemoteAddressWhenNoHeader() {
        ServerWebExchange exchange = exchange(null, null, "198.51.100.9");

        assertEquals("ip:198.51.100.9", config.ipKeyResolver().resolve(exchange).block());
    }
}
