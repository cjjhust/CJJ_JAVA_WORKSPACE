package com.aslp.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 认证服务安全配置。
 *
 * <p>历史 Bug：原配置只声明了 {@code PasswordEncoder}，未声明 {@code SecurityFilterChain}，
 * 导致 Spring Boot 默认安全链生效 —— 所有端点（含 {@code /api/auth/health}）均需 Basic 认证，
 * 网关与前端无法调用。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                // 无状态：认证入口自身不依赖 session（JWT 由网关侧校验）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // P1-1：放行 Prometheus 抓取端点。本服务关了 httpBasic，
                        // 不放行的结果是 403（不是 401）——Prometheus 侧只看到「403」，
                        // 排查时容易误判成权限配置问题。
                        // 权衡：指标不含业务数据；生产应改用独立 management 端口 + 来源限制。
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
