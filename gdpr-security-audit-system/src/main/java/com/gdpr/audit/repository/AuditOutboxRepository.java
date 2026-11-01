package com.gdpr.audit.repository;

import com.gdpr.audit.entity.AuditOutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface AuditOutboxRepository extends JpaRepository<AuditOutboxEvent, Long> {

    List<AuditOutboxEvent> findByStatusOrderByCreatedAtAsc(AuditOutboxEvent.OutboxStatus status, Pageable pageable);

    boolean existsByAuditLogIdAndStatus(Long auditLogId, AuditOutboxEvent.OutboxStatus status);

    long countByStatus(AuditOutboxEvent.OutboxStatus status);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE audit_outbox_events
               SET status = 'SENT', sent_at = :sentAt, lease_owner = NULL, lease_until = NULL
             WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner
            """, nativeQuery = true)
    int markSentIfOwned(@Param("id") Long id, @Param("owner") String owner,
                        @Param("sentAt") Instant sentAt);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE audit_outbox_events
               SET attempts = attempts + 1,
               status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                   lease_owner = NULL, lease_until = NULL, next_attempt_at = :nextAttemptAt
             WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner
            """, nativeQuery = true)
    int releaseAfterFailure(@Param("id") Long id, @Param("owner") String owner,
                            @Param("maxAttempts") int maxAttempts,
                            @Param("nextAttemptAt") Instant nextAttemptAt);

    @Modifying
    @Transactional
    @Query(value = """
            UPDATE audit_outbox_events
               SET status = 'PENDING', attempts = 0, sent_at = NULL,
                   lease_owner = NULL, lease_until = NULL, next_attempt_at = NULL
             WHERE id = :id AND status = 'FAILED'
            """, nativeQuery = true)
    int replayFailed(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM AuditOutboxEvent e WHERE e.status = 'SENT' AND e.sentAt < :before")
    int deleteSentBefore(@Param("before") java.time.Instant before);
}
