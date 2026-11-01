package com.gdpr.audit.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Map;

/**
 * 安全上下文工具类 — 从当前请求的 JWT 中提取操作人信息，供审计切面使用。
 */
public final class SecurityUtils {

    public static final String ANONYMOUS = "anonymous";

    private SecurityUtils() {
    }

    /** 当前用户 ID（JWT sub），未认证时返回 "anonymous" */
    public static String getCurrentUserId() {
        Jwt jwt = getCurrentJwt();
        return jwt != null ? jwt.getSubject() : ANONYMOUS;
    }

    /** 当前用户名（JWT preferred_username），未认证时返回 "anonymous" */
    public static String getCurrentUsername() {
        Jwt jwt = getCurrentJwt();
        return jwt != null ? jwt.getClaimAsString("preferred_username") : ANONYMOUS;
    }

    /** 当前用户角色列表（realm_access.roles），逗号分隔；未认证时返回空串 */
    public static String getCurrentUserRoles() {
        Jwt jwt = getCurrentJwt();
        if (jwt == null) {
            return "";
        }
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return "";
        }
        Object roles = realmAccess.get("roles");
        if (roles instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        }
        return "";
    }

    /** 从 SecurityContext 中取出当前 JWT；非 JWT 认证方式返回 null */
    private static Jwt getCurrentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        return null;
    }
}
