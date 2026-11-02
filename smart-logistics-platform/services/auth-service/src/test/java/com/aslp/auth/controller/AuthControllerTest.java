package com.aslp.auth.controller;

import com.aslp.auth.entity.AuthUser;
import com.aslp.auth.service.AuthService;
import com.aslp.auth.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-6 登录端点单测（不启动 Spring 上下文 / 不连库）。
 *
 * <p>锁定登录契约：成功 200 + 令牌，认证失败 401 + 不区分原因的同一错误体。
 */
class AuthControllerTest {

    private JwtTokenService tokenService;
    private AuthService authService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        tokenService = mock(JwtTokenService.class);
        authService = mock(AuthService.class);
        controller = new AuthController(tokenService, authService);
        when(tokenService.getTtlMinutes()).thenReturn(120L);
    }

    @Test
    @DisplayName("正确凭据：200，返回令牌与角色（ADMIN 带 USER 语义）")
    void loginSuccessReturnsTokenAndRoles() {
        when(authService.authenticate("admin", "admin123"))
                .thenReturn(Optional.of(new AuthUser("admin", "$2a$10$dummy", "ADMIN")));
        when(tokenService.issueToken(eq("admin"), anyList())).thenReturn("test-token");

        ResponseEntity<Map<String, Object>> response =
                controller.login(new AuthController.LoginRequest("admin", "admin123"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("test-token", response.getBody().get("token"));
        assertEquals(List.of("ADMIN", "USER"), response.getBody().get("roles"));
        assertEquals("admin", response.getBody().get("username"));
    }

    @Test
    @DisplayName("密码错误 / 用户不存在：统一 401 + invalid_credentials（防用户枚举）")
    void loginFailureReturns401WithoutLeakingReason() {
        when(authService.authenticate("admin", "wrong")).thenReturn(Optional.empty());
        when(authService.authenticate("ghost", "whatever")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> wrongPassword =
                controller.login(new AuthController.LoginRequest("admin", "wrong"));
        ResponseEntity<Map<String, Object>> unknownUser =
                controller.login(new AuthController.LoginRequest("ghost", "whatever"));

        assertEquals(HttpStatus.UNAUTHORIZED, wrongPassword.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, unknownUser.getStatusCode());
        // 两种失败必须返回完全相同的响应体（防枚举契约）
        assertEquals(wrongPassword.getBody(), unknownUser.getBody());
        assertEquals("invalid_credentials", wrongPassword.getBody().get("error"));
    }

    @Test
    @DisplayName("非 ADMIN 角色：JWT roles 只含自身角色（不再靠用户名前缀提权）")
    void operatorGetsOnlyOperatorRole() {
        when(authService.authenticate("operator", "operator123"))
                .thenReturn(Optional.of(new AuthUser("operator", "$2a$10$dummy", "OPERATOR")));
        when(tokenService.issueToken(eq("operator"), anyList())).thenReturn("t");

        ResponseEntity<Map<String, Object>> response =
                controller.login(new AuthController.LoginRequest("operator", "operator123"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of("OPERATOR"), response.getBody().get("roles"));
        assertTrue(!((List<String>) response.getBody().get("roles")).contains("ADMIN"));
    }
}
