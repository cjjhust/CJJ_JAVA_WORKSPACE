package com.gdpr.audit.repository;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
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
            assertThat(sourceData).isNotEqualTo(restoredData);
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

    private void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}