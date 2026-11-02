package com.aslp.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.ArrayList;
import java.util.List;

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
 *   <li><b>P2-3 客户端 IP 防伪造</b>：原实现无条件取 {@code X-Forwarded-For} 首跳 ——
 *       任何客户端都能自己写这个头，把登录爆破/限流打到自己伪造的"别人"头上。
 *       现在只有当<b>直接对端（TCP 远端）在可信代理清单</b>（{@code aslp.security.trusted-proxies}，
 *       IP 或 IPv4 CIDR，逗号分隔）内时才采信 XFF，且按标准的「右向左」算法解析：
 *       从最右一跳开始，当且仅当该跳的"发送方"是可信代理时，才把它作为可信的客户端地址候选；
 *       遇到不可信发送方立即停止。默认清单为空（严格模式，XFF 一律不采信），
 *       网关直连时伪造头必然失效；docker profile 按 compose 网段放开（见 {@code application-docker.yml}）。</li>
 * </ul>
 */
@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    private static final String UNKNOWN_CLIENT = "unknown-client";

    /** 可信代理规则（默认空 = 严格模式，XFF 不采信）。 */
    private final List<CidrRule> trustedProxies;

    /**
     * Spring 注入用：{@code aslp.security.trusted-proxies}（逗号分隔的 IP / IPv4 CIDR）。
     * 默认空 —— <b>不配置 = 不信任任何代理</b>（失败安全：伪造 XFF 必然无效）。
     */
    @Autowired
    public RateLimitConfig(@Value("${aslp.security.trusted-proxies:}") String trustedProxies) {
        List<CidrRule> rules = new ArrayList<>();
        for (String part : trustedProxies.split(",")) {
            CidrRule rule = CidrRule.parse(part.trim());
            if (rule != null) {
                rules.add(rule);
            }
        }
        this.trustedProxies = List.copyOf(rules);
    }

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

    /**
     * 解析真实客户端 IP（P2-3）。
     *
     * <ol>
     *   <li>无 XFF 头 → 直接对端地址；</li>
     *   <li>有 XFF 头 → 「右向左」：hops[n-1] 的发送方是 TCP 对端，hops[i] 的发送方是 hops[i+1]；
     *       从右往左走，发送方可信则继续、记录候选，发送方不可信则整个链条到此为止。
     *       最终候选即"可信代理链最深处报告的那个地址"；走不出任何可信跳 → 回退 TCP 对端。</li>
     * </ol>
     */
    private String resolveClientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String peer = (remote == null || remote.getAddress() == null)
                ? null : remote.getAddress().getHostAddress();
        String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");

        if (!StringUtils.hasText(forwardedFor)) {
            return peer == null ? UNKNOWN_CLIENT : peer;
        }

        List<String> hops = new ArrayList<>();
        for (String hop : forwardedFor.split(",")) {
            if (StringUtils.hasText(hop)) {
                hops.add(hop.trim());
            }
        }

        String candidate = null;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String sender = (i == hops.size() - 1) ? peer : hops.get(i + 1);
            if (isTrustedProxy(sender)) {
                candidate = hops.get(i);
            } else {
                break;   // 发送方不可信：这一跳及更左的地址都是客户端自己写的
            }
        }
        return candidate != null ? candidate : (peer == null ? UNKNOWN_CLIENT : peer);
    }

    private boolean isTrustedProxy(String ip) {
        if (ip == null) {
            return false;
        }
        return trustedProxies.stream().anyMatch(rule -> rule.matches(ip));
    }

    /**
     * IPv4 精确地址或 CIDR 规则。
     *
     * <p><b>只接受 IPv4 字面量</b>：IPv6 与主机名一律拒绝（记 WARN）。
     * 拒绝而不是猜测是刻意的 —— 误把主机名写进可信清单会引入 DNS 解析依赖，
     * 而 IPv6 的 CIDR 解析本类不做（本项目网关的代理出口均为 IPv4）。
     *
     * <p><b>内部用 {@code long} 承载 32 位值</b>：int 会把首段 ≥128 的地址
     * （172.x / 192.168.x 等，恰好是内网常用段）表示为负数，与「解析失败」
     * 哨兵 -1 冲突 —— 用 long（0..0xFFFFFFFF）则无歧义。
     */
    private record CidrRule(String raw, long network, long mask) {

        static CidrRule parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String addr = text;
            int maskBits = 32;
            int slash = text.indexOf('/');
            if (slash >= 0) {
                addr = text.substring(0, slash);
                try {
                    maskBits = Integer.parseInt(text.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (maskBits < 0 || maskBits > 32) {
                    return null;
                }
            }
            long value = ipv4ToLong(addr);
            if (value < 0) {
                log.warn("[限流] 忽略非法的可信代理规则（仅支持 IPv4 / IPv4-CIDR）：{}", text);
                return null;
            }
            long mask = maskBits == 0 ? 0L : (0xFFFFFFFFL << (32 - maskBits));
            return new CidrRule(text, value & mask, mask);
        }

        boolean matches(String ip) {
            long value = ipv4ToLong(ip);
            return value >= 0 && (value & mask) == network;
        }

        /**
         * IPv4 字面量 → long（0..0xFFFFFFFF）；非 IPv4 返回 -1。
         *
         * <p><b>纯文本解析，不做任何 DNS 查询</b>：XFF 是客户端可控输入，
         * 用 {@code Inet4Address.getByName} 会让恶意主机名触发 DNS 解析
         * （延迟 + 解析器攻击面），这里逐段校验数字即可。
         */
        static long ipv4ToLong(String ip) {
            if (ip == null) {
                return -1;
            }
            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                return -1;
            }
            long value = 0;
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    return -1;
                }
                int octet = 0;
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c < '0' || c > '9') {
                        return -1;
                    }
                    octet = octet * 10 + (c - '0');
                }
                if (octet > 255) {
                    return -1;
                }
                value = (value << 8) | octet;
            }
            return value;
        }
    }
}
