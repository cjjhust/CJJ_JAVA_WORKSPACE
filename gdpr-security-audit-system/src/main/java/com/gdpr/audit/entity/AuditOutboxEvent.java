package com.gdpr.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Transactional Outbox — 与审计日志同事务写入，保证 DB 与 ELK 至少一次投递。
 *
 * 状态流转：PENDING → SENT（投递成功）/ FAILED（超过最大重试）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "audit_outbox_events",
    indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, createdAt")
    }
)
public class AuditOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对应审计日志 ID（幂等去重依据） */
    @Column(name = "audit_log_id", nullable = false)
    private Long auditLogId;

    /** 审计事件 JSON 载荷（已脱敏，Logstash json_lines 可直接解析） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public enum OutboxStatus { PENDING, SENT, FAILED }
}
