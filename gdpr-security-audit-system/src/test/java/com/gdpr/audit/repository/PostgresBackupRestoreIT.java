package com.gdpr.audit.repository;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("resource")
class PostgresBackupRestoreIT {

    @Test
    void restoresLogicalBackupIntoSeparatePostgresDataVolume() throws Exception {
        Path root = Files.createTempDirectory("audit-pg-restore-");
        Path sourceData = Files.createDirectory(root.resolve("source-data"));
        Path restoredData = Files.createDirectory(root.resolve("restored-data"));
        PostgreSQLContainer<?> source = postgres("audit_backup_source", sourceData);
        PostgreSQLContainer<?> restored = postgres("audit_backup_restore", restoredData);

        try (source; restored) {
            source.start();
            restored.start();
            execute(source, "psql", "-U", "audit_test", "-d", "audit_db", "-v", "ON_ERROR_STOP=1",
                    "-c", "CREATE TABLE recovery_probe (id INTEGER PRIMARY KEY, marker TEXT NOT NULL); "
                            + "INSERT INTO recovery_probe VALUES (1, 'restored-from-backup');");

            var dump = source.execInContainer("sh", "-c",
                    "pg_dump -Fc -U audit_test -d audit_db | base64 | tr -d '\\n'");
            assertThat(dump.getExitCode()).isZero();
            String archive = dump.getStdout().trim();
            assertThat(archive).isNotBlank();

            var restore = restored.execInContainer("sh", "-c",
                    "printf '%s' '" + archive + "' | base64 -d > /tmp/audit-backup.dump "
                            + "&& pg_restore --no-owner --no-acl -U audit_test -d audit_db /tmp/audit-backup.dump");
            assertThat(restore.getExitCode()).isZero().withFailMessage("pg_restore failed: %s", restore.getStderr());

            var result = restored.execInContainer("psql", "-U", "audit_test", "-d", "audit_db", "-At",
                    "-c", "SELECT marker FROM recovery_probe WHERE id = 1");
            assertThat(result.getExitCode()).isZero();
            assertThat(result.getStdout().trim()).isEqualTo("restored-from-backup");
            // 恢复发生在独立的容器与独立的数据目录上，而不是同一个实例
            assertThat(restored.getContainerId()).isNotEqualTo(source.getContainerId());
        } finally {
            deleteTree(root);
        }
    }

    @SuppressWarnings("resource")
    private PostgreSQLContainer<?> postgres(String name, Path dataDirectory) {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("audit_db")
                .withUsername("audit_test")
                .withPassword("test-only")
                .withFileSystemBind(dataDirectory.toAbsolutePath().toString(), "/var/lib/postgresql/data",
                    org.testcontainers.containers.BindMode.READ_WRITE)
                .withCreateContainerCmdModifier(command -> command.withName(name));
    }

    private void execute(PostgreSQLContainer<?> container, String... command) throws Exception {
        var result = container.execInContainer(command);
        assertThat(result.getExitCode()).isZero()
                .withFailMessage("Command failed: %s", result.getStderr());
    }

    /**
     * 尽力删除临时目录。
     *
     * Linux 上 PostgreSQL 容器会以 uid 999、权限 700 写入绑定目录，宿主机用户无法
     * 遍历/删除这些目录（macOS 的 Docker Desktop 经 VM 映射掩益了这一点）。清理
     * 失败不属于恢复验证失败，只输出提示；CI runner 为一次性环境。
     */
    private void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException denied) {
                    System.err.println("Best-effort cleanup skipped: " + path
                            + " (" + denied.getClass().getSimpleName() + ")");
                }
            }
        } catch (IOException | UncheckedIOException ex) {
            System.err.println("Best-effort cleanup skipped: " + root
                    + " (" + ex.getClass().getSimpleName() + ")");
        }
    }
}