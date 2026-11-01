package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.repository.AuditChainHeadRepository;
import com.gdpr.audit.repository.AuditLogRepository;
import com.gdpr.audit.repository.AuditOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计链指标绑定（Task 40）— 周期采样并暴露给 Micrometer/Prometheus。
 *
 * 指标：
 * - audit_chain_length          当前链长度（最大 chainSequence）
 * - audit_chain_head_next       链头 nextSequence（与 length 比较可发现回退）
 * - audit_chain_verification    最近一次链校验结果（1=valid，0=invalid）
 * - audit_outbox_pending_depth  Outbox PENDING 事件数（延迟指标）
 * - audit_outbox_failed_depth   Outbox FAILED 事件数（死信告警来源）
 */
@Slf4j
@Component
public class AuditChainMetrics {

    private final AuditLogRepository auditLogRepository;
    private final AuditChainHeadRepository headRepository;
    private final AuditOutboxRepository outboxRepository;
    private final AuditChainVerifier chainVerifier;

    private volatile boolean lastVerificationValid = true;

    public AuditChainMetrics(MeterRegistry registry,
                             AuditLogRepository auditLogRepository,
                             AuditChainHeadRepository headRepository,
                             AuditOutboxRepository outboxRepository,
                             AuditChainVerifier chainVerifier) {
        this.auditLogRepository = auditLogRepository;
        this.headRepository = headRepository;
        this.outboxRepository = outboxRepository;
        this.chainVerifier = chainVerifier;

        Gauge.builder("audit_chain_length", this, m -> {
            Long max = m.auditLogRepository.findMaxChainSequence();
            return max == null ? 0d : max.doubleValue();
        }).description("Audit chain length (max chainSequence)").register(registry);

        Gauge.builder("audit_chain_head_next", this, m -> {
            var head = m.headRepository.findById(AuditChainService.STREAM_ID).orElse(null);
            return head == null ? 0d : head.getNextSequence();
        }).description("Audit chain head nextSequence").register(registry);

        Gauge.builder("audit_chain_verification", this, m -> m.lastVerificationValid ? 1d : 0d)
                .description("Last audit chain verification result (1=valid, 0=invalid)")
                .register(registry);

        Gauge.builder("audit_outbox_pending_depth", this, m ->
                        (double) m.outboxRepository.countByStatus(AuditOutboxEvent.OutboxStatus.PENDING))
                .description("Outbox PENDING event count").register(registry);

        Gauge.builder("audit_outbox_failed_depth", this, m ->
                        (double) m.outboxRepository.countByStatus(AuditOutboxEvent.OutboxStatus.FAILED))
                .description("Outbox FAILED event count").register(registry);
    }

    /**
     * 周期执行链校验并更新指标值（独立于启动校验，运行中篡改也能被发现）。
     */
    @Scheduled(fixedDelayString = "${audit.chain.verify-interval-ms:60000}")
    public void verifyPeriodically() {
        try {
            lastVerificationValid = chainVerifier.verify().valid();
            if (!lastVerificationValid) {
                log.error("Periodic audit chain verification FAILED — check audit_chain_verification metric");
            }
        } catch (Exception ex) {
            log.warn("Audit chain verification errored: {}", ex.getMessage());
        }
    }
}
