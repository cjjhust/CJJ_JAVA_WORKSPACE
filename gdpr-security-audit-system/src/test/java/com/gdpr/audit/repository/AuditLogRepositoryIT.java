package com.gdpr.audit.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.entity.User;
import com.gdpr.audit.aspect.AuditAspect;
import com.gdpr.audit.annotation.Auditable;
import com.gdpr.audit.dto.UserDTO;
import com.gdpr.audit.enums.AuditAction;
import com.gdpr.audit.service.AuditChainService;
import com.gdpr.audit.service.AuditChainStartupValidator;
import com.gdpr.audit.service.AuditChainVerification;
import com.gdpr.audit.service.AuditChainVerifier;
import com.gdpr.audit.service.AuditEventRecorder;
import com.gdpr.audit.service.AuditOutboxClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.time.LocalDateTime;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "audit.chain.hmac-key=test-only-hmac-key-material-at-least-32-bytes",
    "audit.chain.key-id=test-key-v1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditChainService.class, AuditChainVerifier.class, AuditChainStartupValidator.class,
    AuditEventRecorder.class, AuditAspect.class, com.gdpr.audit.service.AuditOutboxPublisher.class,
    AuditOutboxClaimService.class,
    AuditLogRepositoryIT.JsonConfiguration.class})
@Testcontainers
@SuppressWarnings({"null", "resource"})
class AuditLogRepositoryIT {

    static class AuditedOperation {
        @Auditable(action = AuditAction.CREATE, resourceType = "User")
        public ResponseEntity<UserDTO> create() {
            return ResponseEntity.ok().build();
        }
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean
        ObjectMapper testObjectMapper() {
            return new ObjectMapper();
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AuditLogRepository repository;

    @Autowired
    private AuditChainHeadRepository chainHeadRepository;

    @Autowired
    private AuditChainService auditChainService;

    @Autowired
    private AuditChainVerifier auditChainVerifier;

    @Autowired
    private AuditEventRecorder auditEventRecorder;

    @Autowired
    private AuditAspect auditAspect;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditOutboxRepository outboxRepository;

    @Autowired
    private AuditOutboxClaimService outboxClaimService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanRepository() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_audit_logs_no_update ON audit_logs");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_audit_logs_no_delete ON audit_logs");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_outbox_delete_only_expired_sent ON audit_outbox_events");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_chain_heads_no_delete ON audit_chain_heads");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_chain_heads_no_rollback ON audit_chain_heads");
        outboxRepository.deleteAll();
        repository.deleteAll();
        chainHeadRepository.deleteAll();
        chainHeadRepository.flush();
        entityManager.clear();
        chainHeadRepository.save(new AuditChainHead(
            AuditChainService.STREAM_ID,
            1,
            AuditChainService.GENESIS_HASH,
            java.time.Instant.now(),
            0));
    }

    @Test
    void persistsAuditLogAndQueriesByUserInTimestampOrder() {
        AuditLog older = save("user-1", AuditAction.VIEW, "2026-01-01T10:00:00");
        AuditLog newer = save("user-1", AuditAction.DELETE, "2026-01-01T11:00:00");
        save("user-2", AuditAction.VIEW, "2026-01-01T12:00:00");

        AuditLog loaded = repository.findById(older.getId()).orElseThrow();
        List<AuditLog> userLogs = repository.findByUserIdOrderByTimestampDesc("user-1");

        assertThat(loaded.getUsername()).isEqualTo("user-1");
        assertThat(userLogs).extracting(AuditLog::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void filtersByActionAndTimeRangeAndCountsRecentActions() {
        LocalDateTime start = LocalDateTime.parse("2026-01-01T09:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-01-01T12:00:00");
        save("user-1", AuditAction.DELETE, "2026-01-01T10:00:00");
        save("user-1", AuditAction.DELETE, "2026-01-01T11:00:00");
        save("user-1", AuditAction.VIEW, "2026-01-01T11:30:00");
        save("user-1", AuditAction.DELETE, "2026-01-02T10:00:00");

        assertThat(repository.findByActionAndTimestampBetween(AuditAction.DELETE, start, end))
                .hasSize(2);
        assertThat(repository.countRecentActionsByUser("user-1", start)).isEqualTo(4);
        assertThat(repository.findByResourceTypeOrderByTimestampDesc("User")).hasSize(4);
    }

    @Test
    void createsIndexesForUserTimestampAndActionTimestampQueries() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_logs'", String.class);

        assertThat(indexes).contains("idx_audit_user_time", "idx_audit_action_time");
    }

        @Test
        void appendsAndDetectsTamperingInHmacAuditChain() {
        AuditLog first = auditChainService.append(AuditLog.builder()
            .userId("user-1")
            .username("admin-user")
            .userRoles("ROLE_ADMIN")
            .action(AuditAction.CREATE)
            .resourceType("User")
            .resourceId("10")
            .success(true)
            .build());
        AuditLog second = auditChainService.append(AuditLog.builder()
            .userId("user-1")
            .username("admin-user")
            .userRoles("ROLE_ADMIN")
            .action(AuditAction.DELETE)
            .resourceType("User")
            .resourceId("10")
            .success(true)
            .build());

        assertThat(first.getChainSequence()).isEqualTo(1);
        assertThat(first.getPreviousHash()).isEqualTo(AuditChainService.GENESIS_HASH);
        assertThat(second.getChainSequence()).isEqualTo(2);
        assertThat(second.getPreviousHash()).isEqualTo(first.getRecordHash());
        AuditChainVerification beforeTampering = auditChainVerifier.verify();
        assertThat(beforeTampering.valid()).withFailMessage("Unexpected initial verification result: %s", beforeTampering)
            .isTrue();

        jdbcTemplate.update("UPDATE audit_logs SET username = ? WHERE id = ?", "tampered", first.getId());
        entityManager.clear();

        AuditChainVerification verification = auditChainVerifier.verify();
        assertThat(verification.valid()).isFalse();
        assertThat(verification.firstInvalidSequence()).isEqualTo(1L);
        assertThat(verification.reason()).isEqualTo("record HMAC mismatch");
        }

    @Test
    void recordsAuditLogAndOutboxEventTogether() {
        AuditLog saved = auditEventRecorder.record(AuditLog.builder()
                .userId("user-1")
                .username("admin-user")
                .userRoles("ROLE_ADMIN")
                .action(AuditAction.DELETE)
                .resourceType("User")
                .resourceId("10")
                .success(true)
                .build());

        assertThat(repository.findById(saved.getId())).isPresent();
        assertThat(outboxRepository.findByStatusOrderByCreatedAtAsc(
                com.gdpr.audit.entity.AuditOutboxEvent.OutboxStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(outbox -> {
                    assertThat(outbox.getAuditLogId()).isEqualTo(saved.getId());
                    assertThat(outbox.getPayload()).contains("\"action\":\"DELETE\"", "\"severity\":\"HIGH\"");
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollsBackAuditChainWhenOutboxInsertFails() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_outbox_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced outbox failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_outbox_insert_trigger
                BEFORE INSERT ON audit_outbox_events
                FOR EACH ROW EXECUTE FUNCTION reject_outbox_insert()
                """);

        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> auditEventRecorder.record(AuditLog.builder()
                    .userId("user-1")
                    .username("admin-user")
                    .userRoles("ROLE_ADMIN")
                    .action(AuditAction.CREATE)
                    .resourceType("User")
                    .resourceId("11")
                    .success(true)
                    .build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to enqueue audit event");

            assertThat(repository.count()).isZero();
            assertThat(chainHeadRepository.findById(AuditChainService.STREAM_ID)
                    .orElseThrow().getNextSequence()).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_outbox_insert_trigger ON audit_outbox_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_outbox_insert()");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failsClosedAndRollsBackBusinessWriteWhenAuditOutboxFails() throws Throwable {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_outbox_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'forced outbox failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER reject_outbox_insert_trigger
                BEFORE INSERT ON audit_outbox_events
                FOR EACH ROW EXECUTE FUNCTION reject_outbox_insert()
                """);

        AuditedOperation target = new AuditedOperation();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("create");
        when(signature.getMethod()).thenReturn(AuditedOperation.class.getMethod("create"));
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            userRepository.save(User.builder()
                    .firstName("Fail")
                    .lastName("Closed")
                    .email("fail-closed@example.test")
                    .build());
            return ResponseEntity.ok().body(UserDTO.builder().id(1L).build());
        });

        try {
            assertThatThrownBy(() -> auditAspect.audit(joinPoint))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to enqueue audit event");
            assertThat(userRepository.count()).isZero();
            assertThat(repository.count()).isZero();
            assertThat(chainHeadRepository.findById(AuditChainService.STREAM_ID)
                    .orElseThrow().getNextSequence()).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_outbox_insert_trigger ON audit_outbox_events");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_outbox_insert()");
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentConsumersClaimDisjointOutboxRows() throws Exception {
        for (int i = 0; i < 4; i++) {
            auditEventRecorder.record(AuditLog.builder()
                    .userId("user-" + i)
                    .username("user-" + i)
                    .action(AuditAction.VIEW)
                    .resourceType("User")
                    .resourceId(String.valueOf(i))
                    .success(true)
                    .build());
        }

        java.util.concurrent.ExecutorService consumers = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = consumers.submit(() -> {
                start.await();
                return outboxClaimService.claimBatch("consumer-a", 2,
                        java.time.Instant.now(), java.time.Duration.ofSeconds(30));
            });
            var second = consumers.submit(() -> {
                start.await();
                return outboxClaimService.claimBatch("consumer-b", 2,
                        java.time.Instant.now(), java.time.Duration.ofSeconds(30));
            });
            start.countDown();

            List<Long> firstIds = first.get().stream().map(event -> event.getId()).toList();
            List<Long> secondIds = second.get().stream().map(event -> event.getId()).toList();
            assertThat(firstIds).hasSize(2);
            assertThat(secondIds).hasSize(2);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        } finally {
            consumers.shutdownNow();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredConsumerLeaseCanBeClaimedByAnotherInstance() {
        auditEventRecorder.record(AuditLog.builder()
                .userId("user-1")
                .username("user-1")
                .action(AuditAction.VIEW)
                .resourceType("User")
                .success(true)
                .build());

        java.time.Instant now = java.time.Instant.now();
        List<AuditOutboxEvent> firstClaim = outboxClaimService.claimBatch(
                "consumer-crashed", 1, now, java.time.Duration.ofSeconds(5));
        assertThat(firstClaim).hasSize(1);

        List<AuditOutboxEvent> recoveredClaim = outboxClaimService.claimBatch(
                "consumer-recovery", 1, now.plusSeconds(6), java.time.Duration.ofSeconds(5));
        assertThat(recoveredClaim).extracting(AuditOutboxEvent::getId)
                .containsExactly(firstClaim.getFirst().getId());
        assertThat(recoveredClaim.getFirst().getLeaseOwner()).isEqualTo("consumer-recovery");

        java.time.Instant retryAt = now.plusSeconds(60);
        Integer released = new TransactionTemplate(transactionManager).execute(status ->
            outboxRepository.releaseAfterFailure(
                recoveredClaim.getFirst().getId(), "consumer-recovery", 5, retryAt));
        assertThat(released).isEqualTo(1);
        assertThat(outboxClaimService.claimBatch("consumer-too-early", 1,
            now.plusSeconds(30), java.time.Duration.ofSeconds(5))).isEmpty();
        assertThat(outboxClaimService.claimBatch("consumer-after-backoff", 1,
            now.plusSeconds(61), java.time.Duration.ofSeconds(5))).hasSize(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void restrictedApplicationRoleCanAppendButCannotMutateAuditRows() {
        POSTGRES.copyFileToContainer(
                MountableFile.forHostPath(java.nio.file.Path.of("scripts/db-hardening.sql").toAbsolutePath()),
                "/tmp/db-hardening.sql");
        try {
            var result = POSTGRES.execInContainer("psql", "-U", "test", "-d", "audit_test",
                    "-v", "ON_ERROR_STOP=1", "-f", "/tmp/db-hardening.sql");
            assertThat(result.getExitCode()).isZero().withFailMessage("Hardening SQL failed: %s", result.getStderr());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to execute database hardening SQL", ex);
        }

        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE audit_app");
                try {
                    statement.execute("""
                        INSERT INTO audit_logs(user_id, username, action, resource_type, timestamp)
                        VALUES ('restricted-user', 'restricted-user', 'VIEW', 'User', CURRENT_TIMESTAMP)
                        """);
                        assertThat(statement.executeUpdate("""
                            UPDATE audit_chain_heads
                               SET next_sequence = next_sequence + 1, last_hash = repeat('1', 64)
                             WHERE stream_id = 'global'
                            """)).isEqualTo(1);
                    assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE audit_logs SET username='changed' WHERE user_id='restricted-user'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
                    assertThatThrownBy(() -> statement.executeUpdate(
                        "DELETE FROM audit_logs WHERE user_id='restricted-user'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
                                        assertThatThrownBy(() -> statement.executeUpdate("""
                                                        UPDATE audit_chain_heads
                                                             SET next_sequence = 1
                                                         WHERE stream_id = 'global'
                                                        """))
                                                        .isInstanceOf(SQLException.class)
                                                        .hasMessageContaining("rollback blocked");
                } finally {
                    statement.execute("RESET ROLE");
                }
            }
            return null;
        });
    }

    private AuditLog save(String userId, AuditAction action, String timestamp) {
        AuditLog saved = repository.save(AuditLog.builder()
                .userId(userId)
                .username(userId)
                .userRoles("ROLE_USER")
                .action(action)
                .resourceType("User")
                .timestamp(LocalDateTime.parse(timestamp))
                .success(true)
                .build());
            repository.flush();
            jdbcTemplate.update("UPDATE audit_logs SET timestamp = ? WHERE id = ?",
                LocalDateTime.parse(timestamp), saved.getId());
            entityManager.clear();
            return saved;
    }
}
