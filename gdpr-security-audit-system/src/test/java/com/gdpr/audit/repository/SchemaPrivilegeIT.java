package com.gdpr.audit.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证「initdb 建角色 + Flyway 迁移授权」链路真实可用。
 *
 * 背景：initdb 阶段表还不存在，若把 GRANT 放在 init 脚本会静默失败，导致运行账号
 * audit_app 无任何表权限、应用无法启动。本测试按生产顺序执行：
 * 1. docker/postgres-init/01-create-audit-app.sql（建角色）
 * 2. V1__init.sql（建表，DDL 账号）
 * 3. V2__grant_audit_app.sql（授权）
 * 再以 audit_app 身份验证最小权限边界。
 */
@Testcontainers
@SuppressWarnings({"resource", "null"})
class SchemaPrivilegeIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_test")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void applySchemaLikeProduction() throws Exception {
        runScript(Path.of("docker/postgres-init/01-create-audit-app.sql"));
        runScript(Path.of("src/main/resources/db/migration/V1__init.sql"));
        runScript(Path.of("src/main/resources/db/migration/V2__grant_audit_app.sql"));
    }

    private static void runScript(Path script) throws Exception {
        String sql = Files.readString(script);
        var result = POSTGRES.execInContainer("psql", "-U", "test", "-d", "audit_test",
                "-v", "ON_ERROR_STOP=1", "-c", sql);
        assertThat(result.getExitCode())
                .withFailMessage("Script %s failed: %s", script, result.getStderr())
                .isZero();
    }

    private static org.testcontainers.containers.Container.ExecResult asAuditApp(String sql) throws Exception {
        return POSTGRES.execInContainer("psql", "-U", "test", "-d", "audit_test",
                "-v", "ON_ERROR_STOP=1", "-c", "SET ROLE audit_app; " + sql);
    }

    @Test
    void auditAppCanWriteBusinessAndAppendAuditRows() throws Exception {
        assertThat(asAuditApp("""
                INSERT INTO users(first_name, last_name, email)
                VALUES ('App', 'Writer', 'app.writer@example.test');
                """).getExitCode()).isZero();

        assertThat(asAuditApp("""
                INSERT INTO audit_logs(timestamp, user_id, username, action, resource_type, chain_sequence)
                VALUES (now(), 'u1', 'u1', 'CREATE', 'User', 9001);
                """).getExitCode()).isZero();

        assertThat(asAuditApp("""
                INSERT INTO audit_outbox_events(audit_log_id, payload, status, created_at)
                VALUES (1, '{}', 'PENDING', now());
                """).getExitCode()).isZero();

        assertThat(asAuditApp("""
                UPDATE audit_outbox_events SET status = 'SENT', sent_at = now() WHERE audit_log_id = 1;
                """).getExitCode()).isZero();

        assertThat(asAuditApp("""
                UPDATE audit_chain_heads SET next_sequence = next_sequence + 1, last_hash = repeat('a', 64)
                WHERE stream_id = 'global';
                """).getExitCode()).isZero();

        assertThat(asAuditApp("SELECT count(*) FROM audit_logs;").getExitCode()).isZero();
    }

    @Test
    void auditAppCannotMutateAuditRows() throws Exception {
        var update = asAuditApp("UPDATE audit_logs SET username = 'tampered' WHERE chain_sequence = 9001;");
        assertThat(update.getExitCode()).isNotZero();
        assertThat(update.getStderr()).contains("permission denied");

        var delete = asAuditApp("DELETE FROM audit_logs WHERE chain_sequence = 9001;");
        assertThat(delete.getExitCode()).isNotZero();
        assertThat(delete.getStderr()).contains("permission denied");
    }

    @Test
    void auditAppCannotCreateSchemaObjectsWithFlywayRole() throws Exception {
        // 运行账号无 CREATE：迁移必须由独立 DDL 账号执行
        var create = asAuditApp("CREATE TABLE should_not_exist (id int);");
        assertThat(create.getExitCode()).isNotZero();
        assertThat(create.getStderr()).contains("permission denied");
    }
}
