package com.aslp.auth.controller;

import com.aslp.auth.service.JwtTokenService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtTokenService tokenService;

    public AuthController(JwtTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /** 认证服务健康检查（网关 {@code /api/auth/**} 路由无鉴权放行）。 */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "auth-service up",
                "security", "Spring Security + JWT",
                "rbac", "enabled"
        ));
    }

    /**
     * 用户登录，签发 JWT 访问令牌。
     *
     * <p>演示实现：未接入用户库，按用户名推导角色（admin* → ADMIN + USER，其余 → USER）。
     * 后续 M5 接入真实用户表 + BCrypt 密码校验。
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestParam @NotBlank String username) {
        List<String> roles = username.startsWith("admin") ? List.of("ADMIN", "USER") : List.of("USER");
        String token = tokenService.issueToken(username, roles);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "username", username,
                "roles", roles,
                "expiresInMinutes", tokenService.getTtlMinutes()
        ));
    }

    /** 便于网关/前端快速验证令牌有效性（实际校验由网关 JWKS 完成）。 */
    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@RequestParam String username) {
        return ResponseEntity.ok(Map.of("username", username, "status", "authenticated"));
    }
}
