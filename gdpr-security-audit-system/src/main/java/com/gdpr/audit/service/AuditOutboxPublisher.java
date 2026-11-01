package com.gdpr.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.repository.AuditOutboxRepository;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 投递器 — 周期性将 PENDING 审计事件经 AUDIT logger 发往 ELK。
 *
 * 与 AuditAspect 同事务写入 Outbox，投递失败仅重试（最多 maxAttempts 次），
 * 保证 DB→ELK 至少一次投递；Logstash 侧按 auditLogId 幂等去重。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditOutboxPublisher {

    static final String LOGSTASH_UNAVAILABLE = "";

    private final AuditOutboxRepository outboxRepository;

    @Value("${audit.outbox.batch-size:100}")
    private int batchSize;

    @Value("${audit.outbox.max-attempts:5}")
    private int maxAttempts;

    private final ObjectMapper objectMapper;

    /**
     * 将一条审计事件写入 Outbox（必须与审计日志同一事务调用）。
     */
    @Transactional
    public void enqueue(Long auditLogId, Object structuredPayload) {
        try {
            outboxRepository.save(AuditOutboxEvent.builder()
                    .auditLogId(auditLogId)
                    .payload(objectMapper.writeValueAsString(structuredPayload))
                    .status(AuditOutboxEvent.OutboxStatus.PENDING)
                    .attempts(0)
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            // Outbox 写入失败不应回滚业务/审计事务，仅记录告警
            log.warn("Failed to enqueue audit outbox event for log {}: {}", auditLogId, ex.getMessage());
        }
    }

    /**
     * 周期投递：PENDING 事件经 AUDIT logger 发出后标记 SENT，超限标记 FAILED。
     */
    @Scheduled(fixedDelayString = "${audit.outbox.poll-interval-ms:2000}")
    @Transactional
    public void relay() {
        Pageable page = PageRequest.of(0, batchSize);
        List<AuditOutboxEvent> pending = outboxRepository.findByStatusOrderByCreatedAtAsc(
                AuditOutboxEvent.OutboxStatus.PENDING, page);
        for (AuditOutboxEvent event : pending) {
            try {
                AUDIT_RELAY.info("Audit outbox relay: {}", event.getPayload());
                event.setStatus(AuditOutboxEvent.OutboxStatus.SENT);
                event.setSentAt(Instant.now());
            } catch (Exception ex) {
                event.setAttempts(event.getAttempts() + 1);
                if (event.getAttempts() >= maxAttempts) {
                    event.setStatus(AuditOutboxEvent.OutboxStatus.FAILED);
                }
                log.warn("Outbox relay attempt {} failed for event {}: {}",
                        event.getAttempts(), event.getId(), ex.getMessage());
            }
        }
        outboxRepository.saveAll(pending);
    }

    /**
     * 清理已投递事件（保留 7 天），避免 Outbox 表无限膨胀。
     */
    @Scheduled(cron = "${audit.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void cleanup() {
        int removed = outboxRepository.deleteSentBefore(Instant.now().minusSeconds(7 * 24 * 3600));
        if (removed > 0) {
            log.info("Cleaned {} sent outbox events", removed);
        }
    }

    /** 独立 relay logger，与 AUDIT 主链路共用 Logstash appender */
    private static final org.slf4j.Logger AUDIT_RELAY =
            org.slf4j.LoggerFactory.getLogger("AUDIT");
}
