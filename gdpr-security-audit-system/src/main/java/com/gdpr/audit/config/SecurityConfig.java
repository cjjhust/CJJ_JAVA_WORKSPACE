package com.gdpr.audit.config;

import com.gdpr.audit.security.JwtRoleConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置 — OAuth2 Resource Server 模式。
 *
 * 核心设计：
 * 1. 应用作为 OAuth2 Resource Server，通过 JWT Token 进行身份验证
 * 2. 无状态 Session（REST API 不使用 Session）
 * 3. 基于 URL 路径的粗粒度 RBAC 权限控制
 * 4. 通过 JwtRoleConverter 将 Keycloak 角色映射到 Spring Security Authority
 *
 * 权限规则：
 * - /api/public/**   → 允许匿名访问
 * - /api/admin/**    → 需要 ROLE_ADMIN
 * - /api/audit/**    → 需要 ROLE_AUDITOR
 * - 其他所有请求     → 需要认证（任意角色）
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // 启用方法级安全注解 (@PreAuthorize, @Secured 等)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRoleConverter jwtRoleConverter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // REST API 不需要 CSRF 防护
            .csrf(csrf -> csrf.disable())

            // 无状态 Session —— 每个请求都通过 JWT Token 独立认证
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // URL 级别的权限控制
            .authorizeHttpRequests(auth -> auth
                // 公开接口：健康检查等
                .requestMatchers("/api/public/**").permitAll()
                // 管理员接口
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 审计员接口
                .requestMatchers("/api/audit/**").hasRole("AUDITOR")
                // 其他所有接口需要认证
                .anyRequest().authenticated()
            )

            // OAuth2 Resource Server 配置 — 使用 JWT + 自定义角色转换器
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtRoleConverter)
                )
            );

        return http.build();
    }
}
