package com.aslp.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

/**
 * P1-2 网关限流（Redis 令牌桶）的 key 解析器 —— 决定「令牌桶归谁」。
 *
 * <p><b>命名空间前缀是必需的，不是风格问题</b>：
 * Spring Cloud Gateway 的 {@code RedisRateLimiter} 生成的 key 形如
 * {@code request_rate_limiter.{<key-resolver 返回值>}.tokens}，<b>不含 routeId</b>
 * （已核对其 4.1.5 字节码：{@code getKeys(String)} 只接收解析器输出）。
 * 因此若两条路由复用同一解析器，它们会共用同一个令牌桶 —— 阈值小的那条会把阈值大的
 * 那条一并限死（实测：{@code burst=1} 的拉取路由导致通用路由也返回 429）。
 * 故本类为两条受控路由提供前缀隔离的解析器：
 * {@code user:<用户>}（平台拉取路由）/ {@code ip:<IP>}（认证入口）。
 *
 * <p><b>限流只挂在按需路由上，不使用 {@code default-filters} 全局限流</b>：
 * 全局限流若与路由级限流复用同一解析器，会共用令牌桶互相拖累；
 * 而改用另一套解析器，则常规查询、端到端脚本等正常流量会被无差别节流（实测：
 * 全局桶被突发流量耗尽后，后续正常请求成片返回 429）。
 * 限流应作用于「真正会放大成平台调用」的入口（{@code POST /api/orders/pull}）与认证入口。
 *
 * <p>其余设计要点：
 * <ul>
 *   <li><b>用户维度优先、IP 兜底</b>：登录后按 JWT 主体限流（同一出口 NAT 的多个用户互不挤兑）；
 *       匿名请求回落 IP，避免"只按 IP"把整间办公室算作一个调用方。</li>
 *   <li><b>绝不返回空 key</b>：Spring Cloud Gateway 的 {@code deny-empty-key} 默认为 true，
 *       空 key 会直接 403 —— 那等于把"限流"变成"拒绝服务"。</li>
 *   <li>令牌桶本身由框架的 {@code RedisRateLimiter} 实现（Redis 存储、可水平扩展），
 *       本类只负责归属键，阈值在 {@code application*.yml} 的 RequestRateLimiter 参数里配置。</li>
 *   <li><b>为何 {@code userKeyResolver} 标 {@code @Primary}</b>：本类向容器注入了两个
 *       {@code KeyResolver}，而 {@code GatewayAutoConfiguration#requestRateLimiterGatewayFilterFactory}
 *       需要唯一的 {@code KeyResolver} 作为默认值；不指定主 bean 会导致网关启动失败：
 *       <pre>Parameter 1 of method requestRateLimiterGatewayFilterFactory ... required a single bean, but 2 were found</pre>
 *       路由内仍可用 SpEL 显式引用具体解析器（如 {@code #{@ipKeyResolver}}）。</li>
 * </ul>
 */
@Configuration
public class RateLimitConfig {

    private static final String UNKNOWN_CLIENT = "unknown-client";

    /** 平台拉取路由：用户维度（匿名回落 IP）。 */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> resolveUserOrIp(exchange).map(key -> "user:" + key);
    }

    /** 认证入口：按客户端 IP（登录前无用户身份，只能按 IP 维度防暴力破解）。 */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + resolveClientIp(exchange));
    }

    private Mono<String> resolveUserOrIp(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(this::principalKey)
                .filter(StringUtils::hasText)
                .switchIfEmpty(Mono.fromSupplier(() -> resolveClientIp(exchange)))
                .defaultIfEmpty(UNKNOWN_CLIENT);
    }

    private String principalKey(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getClaimAsString("preferred_username");
            return StringUtils.hasText(username) ? username : jwt.getSubject();
        }
        return principal.getName();
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return UNKNOWN_CLIENT;
        }
        return remote.getAddress().getHostAddress();
    }
}
