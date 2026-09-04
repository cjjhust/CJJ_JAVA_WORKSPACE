package com.gdpr.audit.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 认证与授权测试 Controller。
 *
 * 提供 4 个端点用于验证 OAuth2 + RBAC 是否正常工作：
 * - /api/public/health   → 无需认证
 * - /api/user/profile    → 需认证（任意角色）
 * - /api/admin/dashboard → 需 ROLE_ADMIN
 * - /api/audit/logs      → 需 ROLE_AUDITOR
 */
@RestController
public class AuthTestController {

    // ========== 公开接口 ==========

    /**
     * 健康检查 — 无需认证。
     */
    @GetMapping("/api/public/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "gdpr-security-audit-system",
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    // ========== 需认证接口 ==========

    /**
     * 用户个人信息 — 需认证，任意角色均可访问。
     * 从 JWT Token 中提取用户信息返回。
     */
    @GetMapping("/api/user/profile")
    public ResponseEntity<Map<String, Object>> getUserProfile(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of(
                "userId", jwt.getSubject(),
                "username", jwt.getClaimAsString("preferred_username"),
                "email", getClaimOrDefault(jwt, "email", "N/A"),
                "roles", extractRoles(jwt),
                "tokenIssuedAt", jwt.getIssuedAt().toString(),
                "tokenExpiresAt", jwt.getExpiresAt().toString()
        ));
    }

    // ========== 管理员接口 ==========

    /**
     * 管理员仪表盘 — 需 ROLE_ADMIN 角色。
     * URL 级别权限由 SecurityConfig 控制，方法级别通过 @PreAuthorize 双重保障。
     */
    @GetMapping("/api/admin/dashboard")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> adminDashboard(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(Map.of(
                "message", "Welcome to Admin Dashboard",
                "admin", jwt.getClaimAsString("preferred_username"),
                "systemInfo", Map.of(
                        "javaVersion", System.getProperty("java.version"),
                        "osName", System.getProperty("os.name"),
                        "availableProcessors", Runtime.getRuntime().availableProcessors(),
                        "maxMemoryMB", Runtime.getRuntime().maxMemory() / (1024 * 1024)
                ),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    // ========== 审计员接口 ==========

    /**
     * 审计日志查询 — 需 ROLE_AUDITOR 角色。
     * 当前返回模拟数据，后续会对接真实的 AuditLog 数据库查询。
     */
    @GetMapping("/api/audit/logs")
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<Map<String, Object>> getAuditLogs(@AuthenticationPrincipal Jwt jwt) {
        // 模拟审计日志数据 — 后续由 AuditLogRepository 替代
        List<Map<String, String>> mockLogs = List.of(
                Map.of(
                        "timestamp", "2026-08-10T10:30:00",
                        "userId", "user-001",
                        "action", "VIEW",
                        "resourceType", "User",
                        "resourceId", "42",
                        "success", "true"
                ),
                Map.of(
                        "timestamp", "2026-08-10T10:31:00",
                        "userId", "user-002",
                        "action", "DELETE",
                        "resourceType", "Document",
                        "resourceId", "99",
                        "success", "true"
                ),
                Map.of(
                        "timestamp", "2026-08-10T10:32:00",
                        "userId", "user-003",
                        "action", "DOWNLOAD",
                        "resourceType", "Report",
                        "resourceId", "15",
                        "success", "false"
                )
        );

        return ResponseEntity.ok(Map.of(
                "queriedBy", jwt.getClaimAsString("preferred_username"),
                "totalRecords", mockLogs.size(),
                "logs", mockLogs,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    // ========== 辅助方法 ==========

    /**
     * 从 JWT 中提取 realm_access.roles 角色列表。
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Collection<String> roles = (Collection<String>) realmAccess.get("roles");
        return roles != null ? List.copyOf(roles) : List.of();
    }

    /**
     * 安全获取 JWT Claim，找不到时返回默认值。
     */
    private String getClaimOrDefault(Jwt jwt, String claim, String defaultValue) {
        String value = jwt.getClaimAsString(claim);
        return value != null ? value : defaultValue;
    }
}
