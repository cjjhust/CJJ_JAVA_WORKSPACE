package com.aslp.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

/**
 * M4 — 居家办公 VPN 安全接入（Spring Cloud Gateway 为 WebFlux 应用，必须使用响应式安全链）。
 *
 * <p>历史 Bug：原实现注入了 Servlet 的 {@code HttpSecurity} 且方法缺少 {@code @Bean}，
 * 导致该配置从未生效，网关回落到 Spring Boot 默认安全策略（全站 401，浏览器无法访问）。
 *
 * <p>两种安全策略：
 * <ul>
 *   <li>dev（默认 profile）：放行健康检查 / BFF 查询 / 认证入口，便于本地与浏览器联调。</li>
 *   <li>docker（{@code SPRING_PROFILES_ACTIVE=docker}）：强制 JWT 鉴权 + 细粒度角色授权。</li>
 * </ul>
 *
 * <p><b>鉴权方案说明</b>（P0-2）：RS256 非对称签名——网关经 auth-service 的
 * JWKS 端点（{@code /api/auth/jwks.json}）获取公钥验签，自身不持有任何密钥；
 * 私钥仅存在于 auth-service，轮换 = 换 kid 发新公钥，网关零改动。
 */
@Configuration
@EnableWebFluxSecurity
public class VpnSecurityConfig {

    /** 细粒度权限：BFF 订单查询需要 USER 角色；库存写入需要 ADMIN 角色。 */
    private static final String ROLE_USER = "USER";
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * 开发环境安全链：放行公开端点，关闭 CSRF / Basic / Form 登录，便于本地启动与浏览器验证。
     */
    @Bean
    @Profile("!docker")
    public SecurityWebFilterChain devSecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/bff/**", "/api/auth/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyExchange().permitAll())
                .build();
    }

    /**
     * VPN 安全接入安全链：JWT 细粒度权限控制（oauth2ResourceServer + 角色校验）。
     */
    @Bean
    @Profile("docker")
    public SecurityWebFilterChain vpnSecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // P1-1：Prometheus 抓取端点放行。
                        // 权衡：指标只有计数/耗时，不含业务数据；但它是「内部观测面」，
                        // 生产环境应改用独立 management 端口 + 网络策略限制来源（见 readme §10 限制）。
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/bff/orders/search").hasRole(ROLE_USER)
                        .pathMatchers(HttpMethod.POST, "/api/inventory/**").hasRole(ROLE_ADMIN)
                        // P1-2：故障演练开关会影响拉取链路，限管理员可操作
                        .pathMatchers(HttpMethod.POST, "/api/orders/mock/**").hasRole(ROLE_ADMIN)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleAwareJwtConverter())))
                .build();
    }

    /**
     * JWT 解码器：经 auth-service 的 JWKS 端点获取 RS256 公钥（P0-2）。
     *
     * <p>网关只持有<b>公钥</b>（只能验、不能签）——私钥不离开 auth-service，
     * 密钥轮换 = auth 侧换 kid 发新公钥，网关零改动。
     * {@code withJwkSetUri} 惰性拉取（首个令牌到达时），并按 kid 缓存；
     * 走容器网络别名 {@code aslp-auth-service}（compose 内部 DNS）。
     */
    @Bean
    @Profile("docker")
    public ReactiveJwtDecoder jwtDecoder(@Value("${aslp.security.jwt.jwks-uri}") String jwksUri) {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
    }

    /**
     * 角色映射：把 JWT 的 {@code roles} 声明转换为 {@code ROLE_*} 权限，
     * 使 {@code hasRole("USER")} / {@code hasRole("ADMIN")} 生效。
     *
     * <p>默认转换器只读取 {@code scope}/{@code scp} 并生成 {@code SCOPE_*}，
     * 会导致 {@code hasRole} 永远不匹配（403）。
     */
    private Converter<Jwt, Mono<AbstractAuthenticationToken>> roleAwareJwtConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new java.util.ArrayList<>(scopeConverter.convert(jwt));
            java.util.List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.stream()
                        .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .forEach(authorities::add);
            }
            return authorities;
        });
        converter.setPrincipalClaimName("sub");
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
