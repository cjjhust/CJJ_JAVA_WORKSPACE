package com.aslp.auth.service;

import com.aslp.auth.entity.AuthUser;
import com.aslp.auth.repository.AuthUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-6 认证逻辑测试（H2 内存库，不启动 Web 层）。
 *
 * <p>关键回归：
 * <ul>
 *   <li>正确口令必须通过、错误口令必须拒绝；</li>
 *   <li>角色来自数据而不是用户名前缀（{@code admin} 开头不再是提权开关）；</li>
 *   <li>「用户不存在」与「密码错误」表现一致（防枚举契约）。</li>
 * </ul>
 */
@DataJpaTest
class AuthServiceTest {

    @Autowired
    private AuthUserRepository repository;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(repository, new BCryptPasswordEncoder());
        repository.save(new AuthUser("admin", new BCryptPasswordEncoder().encode("admin123"), "ADMIN"));
        repository.save(new AuthUser("operator", new BCryptPasswordEncoder().encode("operator123"), "OPERATOR"));
    }

    @Test
    @DisplayName("正确口令：返回用户及其角色")
    void correctPasswordSucceeds() {
        Optional<AuthUser> user = authService.authenticate("admin", "admin123");

        assertTrue(user.isPresent());
        assertEquals("ADMIN", user.get().getRole());
        assertEquals(java.util.List.of("ADMIN", "USER"), AuthService.rolesOf(user.get()));
    }

    @Test
    @DisplayName("错误口令：拒绝（不泄露与「用户不存在」的差异）")
    void wrongPasswordIsRejected() {
        assertFalse(authService.authenticate("admin", "wrong-password").isPresent());
    }

    @Test
    @DisplayName("不存在的用户：拒绝（与错误口令同样返回 empty）")
    void unknownUserIsRejected() {
        assertFalse(authService.authenticate("ghost", "any-password").isPresent());
    }

    @Test
    @DisplayName("角色来自用户行：operator 不因为名字不含 admin 就被降级，也不会被前缀逻辑提权")
    void roleComesFromDataNotUsernamePrefix() {
        assertEquals("OPERATOR", authService.authenticate("operator", "operator123").orElseThrow().getRole());
    }

    @Test
    @DisplayName("ADMIN 的 JWT roles 同时带 USER 语义（网关权限映射兼容）")
    void adminRolesIncludeUser() {
        AuthUser admin = repository.findByUsername("admin").orElseThrow();
        assertEquals(java.util.List.of("ADMIN", "USER"), AuthService.rolesOf(admin));
    }
}
