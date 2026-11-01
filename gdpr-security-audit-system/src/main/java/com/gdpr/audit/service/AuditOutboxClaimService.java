package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.repository.AuditOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditOutboxClaimService {

    private final JdbcTemplate jdbcTemplate;
    private final AuditOutboxRepository outboxRepository;

    @Transactional
    @SuppressWarnings("null")
    public List<AuditOutboxEvent> claimBatch(String owner, int batchSize, Instant now, Duration leaseDuration) {
        List<Long> claimedIds = jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id
                      FROM audit_outbox_events
                            WHERE (status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?))
                        OR (status = 'PROCESSING' AND lease_until < ?)
                     ORDER BY created_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE audit_outbox_events AS event
                   SET status = 'PROCESSING', lease_owner = ?, lease_until = ?
                  FROM candidates
                 WHERE event.id = candidates.id
                RETURNING event.id
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                Timestamp.from(now), Timestamp.from(now), batchSize, owner, Timestamp.from(now.plus(leaseDuration)));
        return outboxRepository.findAllById(claimedIds).stream()
                .sorted(java.util.Comparator.comparing(AuditOutboxEvent::getCreatedAt)
                        .thenComparing(AuditOutboxEvent::getId))
                .toList();
    }

    public static String newOwnerId() {
        return UUID.randomUUID().toString();
    }
}