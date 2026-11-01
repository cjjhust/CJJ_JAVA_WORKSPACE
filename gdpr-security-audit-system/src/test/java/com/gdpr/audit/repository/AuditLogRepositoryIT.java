package com.gdpr.audit.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.enums.AuditAction;
import com.gdpr.audit.service.AuditChainService;
import com.gdpr.audit.service.AuditChainStartupValidator;
import com.gdpr.audit.service.AuditChainVerification;
import com.gdpr.audit.service.AuditChainVerifier;
import com.gdpr.audit.service.AuditEventRecorder;
import com.gdpr.audit.repository.AuditOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "audit.chain.hmac-key=test-only-hmac-key-material-at-least-32-bytes",
    "audit.chain.key-id=test-key-v1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditChainService.class, AuditChainVerifier.class, AuditChainStartupValidator.class,
    AuditEventRecorder.class, com.gdpr.audit.service.AuditOutboxPublisher.class,
    AuditLogRepositoryIT.JsonConfiguration.class})
@Testcontainers
class AuditLogRepositoryIT {

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
    private AuditOutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanRepository() {
        repository.deleteAll();
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
