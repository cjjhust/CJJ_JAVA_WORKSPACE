package com.gdpr.audit.security;

import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.enums.AuditAction;
import com.gdpr.audit.gdpr.GdprDataMasker;
import com.gdpr.audit.service.AuditChainService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 安全事件审计过滤器（Task 35）— 将 401/403 认证与授权拒绝事件写入 HMAC 审计链。
 *
 * 与业务 @Auditable 切面互补：即使请求被安全层拒绝（未进入 Controller），
 * 事件仍被完整记录：主体（若可识别）、来源 IP、目标 URI、拒绝原因。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditSecurityEventFilter extends OncePerRequestFilter {

    private final AuditChainService auditChainService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            if (status == 401 || status == 403) {
                recordRejection(request, response, status);
            }
        }
    }

    private void recordRejection(HttpServletRequest request,
                                 HttpServletResponse response,
                                 int status) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            Jwt jwt = auth instanceof JwtAuthenticationToken jwtAuth ? jwtAuth.getToken() : null;

            AuditLog event = AuditLog.builder()
                    .userId(jwt != null ? jwt.getSubject() : SecurityUtils.ANONYMOUS)
                    .username(jwt != null
                            ? String.valueOf(jwt.getClaimAsString("preferred_username"))
                            : SecurityUtils.ANONYMOUS)
                    .userRoles(SecurityUtils.getCurrentUserRoles())
                    .action(status == 401 ? AuditAction.LOGIN : AuditAction.VIEW)
                    .resourceType("SecurityEvent")
                    .resourceId(String.valueOf(status))
                    .requestMethod(request.getMethod())
                    .requestUri(request.getRequestURI())
                    .clientIp(resolveClientIp(request))
                    .userAgent(request.getHeader("User-Agent"))
                    .responseStatus(status)
                    .success(false)
                    .errorMessage(GdprDataMasker.maskPii(
                            status == 401 ? "authentication required or token invalid"
                                          : "access denied by authorization rules"))
                    .build();
            auditChainService.append(event);
        } catch (Exception ex) {
            // 安全事件审计失败不能影响响应已经产生的结果，只告警
            log.warn("Failed to audit security event {} {}: {}",
                    request.getMethod(), request.getRequestURI(), ex.getMessage());
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
