package com.gdpr.audit.controller;

import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 认证与授权测试 Controller。
 *
 * 端点：
 * - /api/public/health   → 无需认证
 * - /api/user/profile    → 需认证（任意角色）
 * - /api/admin/dashboard → 需 ROLE_ADMIN
 * - /api/audit/logs      → 需 ROLE_AUDITOR，查询真实 audit_logs 表
 */
@RestController
@RequiredArgsConstructor
public class AuthTestController {

    private final AuditLogRepository auditLogRepository;

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
                "tokenIssuedAt", String.valueOf(jwt.getIssuedAt()),
                "tokenExpiresAt", String.valueOf(jwt.getExpiresAt())
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
     * 查询真实 audit_logs 表，按时间倒序返回（内容已在写入前脱敏）。
     * 默认 50 条，limit 上限 200。返回字段做了 null 安全处理。
     */
    @GetMapping("/api/audit/logs")
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<Map<String, Object>> getAuditLogs(@AuthenticationPrincipal Jwt jwt,
                                                            @RequestParam(defaultValue = "50") int limit) {
        List<AuditLog> logs = auditLogRepository.findAll(
                PageRequest.of(0, Math.min(limit, 200), Sort.by("timestamp").descending())).getContent();

        List<Map<String, Object>> records = logs.stream()
                .map(this::toRecord)
                .toList();

        return ResponseEntity.ok(Map.of(
                "queriedBy", jwt.getClaimAsString("preferred_username"),
                "totalRecords", records.size(),
                "logs", records,
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    private Map<String, Object> toRecord(AuditLog log) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("timestamp", log.getTimestamp() == null ? "" : log.getTimestamp().toString());
        record.put("userId", nvl(log.getUserId()));
        record.put("username", nvl(log.getUsername()));
        record.put("userRoles", nvl(log.getUserRoles()));
        record.put("action", log.getAction() == null ? "" : log.getAction().name());
        record.put("resourceType", nvl(log.getResourceType()));
        record.put("resourceId", nvl(log.getResourceId()));
        record.put("requestMethod", nvl(log.getRequestMethod()));
        record.put("requestUri", nvl(log.getRequestUri()));
        record.put("responseStatus", log.getResponseStatus());
        record.put("success", log.getSuccess());
        record.put("duration", log.getDuration());
        record.put("chainSequence", log.getChainSequence());
        return record;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
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
