package com.aslp.auth.controller;

import com.aslp.auth.service.AuthService;
import com.aslp.auth.service.JwtTokenService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * P2-6：认证控制器。
 *
 * <p>登录端点从「演示实现」升级为「真实用户 + BCrypt 校验」：
 * <ul>
 *   <li>请求体从 query 参数改为 JSON（{@code username}/{@code password}）——
 *       密码走 URL query 会留在访问日志、浏览器历史与代理缓存里，属于必须修的缺陷；</li>
 *   <li>用户与角色来自 {@code auth_user} 表（Flyway 种子），密码 BCrypt 校验；</li>
 *   <li>认证失败统一 401，不区分「用户不存在 / 密码错误」（防枚举，见 {@link AuthService}）。</li>
 * </ul>
 *
 * <p>历史兼容：冒烟/演示脚本原来用 {@code ?username=admin} 取令牌，
 * 现在需要 {@code -d '{"username":"admin","password":"admin123"}'}
 * （三个脚本已同步更新）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtTokenService tokenService;
    private final AuthService authService;

    public AuthController(JwtTokenService tokenService, AuthService authService) {
        this.tokenService = tokenService;
        this.authService = authService;
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
     * 用户登录，签发 JWT 访问令牌（P2-6：真实用户 + 密码校验）。
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        var user = authService.authenticate(request.username(), request.password());
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_credentials"));
        }
        com.aslp.auth.entity.AuthUser u = user.get();
        var roles = AuthService.rolesOf(u);
        String token = tokenService.issueToken(u.getUsername(), roles);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "username", u.getUsername(),
                "roles", roles,
                "expiresInMinutes", tokenService.getTtlMinutes()
        ));
    }

    /** 登录请求体（密码走 JSON body，不进 URL）。 */
    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    /** 便于网关/前端快速验证令牌有效性（实际校验由网关 JWKS 完成）。 */
    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@RequestParam String username) {
        return ResponseEntity.ok(Map.of("username", username, "status", "authenticated"));
    }
}
