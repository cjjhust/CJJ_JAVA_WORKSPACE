package com.gdpr.audit.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keycloak JWT 角色转换器。
 * 
 * 从 Keycloak 签发的 JWT Token 中提取 realm_access.roles，
 * 并将其转换为 Spring Security 的 GrantedAuthority 列表。
 *
 * Keycloak JWT 中的角色结构示例：
 * {
 *   "realm_access": {
 *     "roles": ["ROLE_ADMIN", "ROLE_USER"]
 *   }
 * }
 */
@Component
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        String username = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, authorities, username);
    }

    /**
     * 从 JWT 的 realm_access.roles 中提取权限列表。
     * 如果角色名称不以 ROLE_ 开头，则自动添加前缀，以兼容 Spring Security 的 hasRole() 判断。
     */
    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return Collections.emptyList();
        }

        Collection<String> roles = (Collection<String>) realmAccess.get(ROLES_KEY);
        if (roles == null) {
            return Collections.emptyList();
        }

        return roles.stream()
                .map(role -> role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
