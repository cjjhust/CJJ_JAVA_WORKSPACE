package com.gdpr.audit.repository;

import com.gdpr.audit.entity.AuditOutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditOutboxRepository extends JpaRepository<AuditOutboxEvent, Long> {

    List<AuditOutboxEvent> findByStatusOrderByCreatedAtAsc(AuditOutboxEvent.OutboxStatus status, Pageable pageable);

    boolean existsByAuditLogIdAndStatus(Long auditLogId, AuditOutboxEvent.OutboxStatus status);

    long countByStatus(AuditOutboxEvent.OutboxStatus status);

    @Modifying
    @Query("DELETE FROM AuditOutboxEvent e WHERE e.status = 'SENT' AND e.sentAt < :before")
    int deleteSentBefore(@Param("before") java.time.Instant before);
}
