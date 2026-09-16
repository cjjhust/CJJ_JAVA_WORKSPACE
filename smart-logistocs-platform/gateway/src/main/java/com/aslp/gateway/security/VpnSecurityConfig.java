package com.aslp.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

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
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/bff/orders/search").hasRole(ROLE_USER)
                        .pathMatchers(HttpMethod.POST, "/api/inventory/**").hasRole(ROLE_ADMIN)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }))
                .build();
    }

    /**
     * JWT 解码器：从 auth-service 暴露的 JWKS 端点获取公钥集合（懒加载，启动时不发起网络请求）。
     */
    @Bean
    @Profile("docker")
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${aslp.security.jwk-set-uri:http://aslp_auth_service:8084/api/auth/.well-known/jwks.json}")
            String jwkSetUri) {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }
}
