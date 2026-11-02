package com.aslp.auth.repository;

import com.aslp.auth.entity.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-8：auth 迁移的「数据库行为」集成测试（真实 PostgreSQL via Testcontainers）。
 *
 * <p>{@code AuthServiceTest}（H2）只验证了 BCrypt 逻辑本身，
 * 而 {@code V1__create_auth_user.sql} 是 PG 方言（TIMESTAMP / now()）——
 * 迁移脚本能不能在真 PG 上跑、种子哈希能不能被真 BCrypt 校验通过，
 * 只有这个测试能给答案。种子账号是冒烟/演示脚本的契约
 * （admin/admin123、operator/operator123），这里把它锁死：
 * 任何人改了种子密码而不更新脚本，CI 就会红。
 */
@Testcontainers
// 同 OrderRepositoryIntegrationTest：禁用 @DataJpaTest 的 H2 替换，
// 否则 DynamicPropertySource 的 PG 容器 URL 被覆盖，迁移跑在 H2 上（假绿或炸方言）
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/auth",
        "spring.flyway.table=flyway_schema_history_auth",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class AuthUserRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aslp")
            .withUsername("aslp")
            .withPassword("aslp123");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AuthUserRepository repository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("种子账号必须能通过 BCrypt 校验（冒烟脚本的契约）")
    void seedUsersAuthenticateWithDocumentedPasswords() {
        AuthUser admin = repository.findByUsername("admin").orElseThrow();
        AuthUser operator = repository.findByUsername("operator").orElseThrow();

        assertTrue(encoder.matches("admin123", admin.getPasswordHash()),
                "admin/admin123 是冒烟脚本依赖的种子契约，改密码必须同步更新脚本");
        assertTrue(encoder.matches("operator123", operator.getPasswordHash()));
        assertEquals("ADMIN", admin.getRole());
        assertEquals("OPERATOR", operator.getRole());
    }

    @Test
    @DisplayName("密码列只存哈希，不可能是明文（长度/前缀特征断言）")
    void passwordColumnStoresHashNotPlaintext() {
        for (AuthUser user : repository.findAll()) {
            assertTrue(user.getPasswordHash().startsWith("$2a$10$"),
                    "auth_user.password_hash 必须是 BCrypt 哈希，实际：" + user.getPasswordHash());
            assertTrue(user.getPasswordHash().length() == 60, "BCrypt 哈希固定 60 字符");
        }
    }
}
