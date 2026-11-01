package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.repository.AuditOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 独立完整性巡检与告警（Task 34）。
 *
 * 消费链校验结果与 Outbox 深度，在检测到以下情况时向独立的 SIEM 告警
 * logger 发送告警（经 logback 单独 appender 输出，不只写回被监控的同一存储）：
 * 1. 链校验失败（篡改/断链/重排）→ ERROR
 * 2. Outbox FAILED 死信出现（投递最终失败）→ WARN
 * 3. Outbox PENDING 积压超过阈值（投递延迟）→ WARN
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditIntegrityMonitor {

    /** 独立 SIEM 告警通道 — 在 logback-spring.xml 中配置独立 appender */
    private static final org.slf4j.Logger SIEM_ALERT =
            org.slf4j.LoggerFactory.getLogger("SIEM_ALERT");

    private final AuditChainVerifier chainVerifier;
    private final AuditOutboxRepository outboxRepository;

    @Value("${audit.monitor.pending-depth-threshold:500}")
    private long pendingThreshold;

    private record Snapshot(boolean chainValid, long pendingDepth, long failedDepth, Instant at) {
    }

    private volatile Snapshot lastSnapshot = new Snapshot(true, 0, 0, Instant.EPOCH);

    /**
     * 周期巡检：链校验 + Outbox 深度检查，异常时发 SIEM 告警。
     */
    @Scheduled(fixedDelayString = "${audit.monitor.interval-ms:30000}")
    public void monitor() {
        boolean chainValid;
        long pending;
        long failed;
        try {
            chainValid = chainVerifier.verify().valid();
            pending = outboxRepository.countByStatus(AuditOutboxEvent.OutboxStatus.PENDING);
            failed = outboxRepository.countByStatus(AuditOutboxEvent.OutboxStatus.FAILED);
        } catch (Exception ex) {
            SIEM_ALERT.warn("event=audit_monitor_error reason={}", ex.getMessage());
            return;
        }

        Snapshot previous = lastSnapshot;
        Snapshot current = new Snapshot(chainValid, pending, failed, Instant.now());
        lastSnapshot = current;

        if (!chainValid) {
            SIEM_ALERT.error("event=audit_chain_invalid detectedAt={} chain_valid=false",
                    current.at());
        }
        if (failed > 0 && previous.failedDepth() == 0) {
            SIEM_ALERT.warn("event=audit_outbox_deadletter detectedAt={} failed_depth={}",
                    current.at(), failed);
        }
        if (pending > pendingThreshold) {
            SIEM_ALERT.warn("event=audit_outbox_backlog detectedAt={} pending_depth={} threshold={}",
                    current.at(), pending, pendingThreshold);
        }
    }
}
