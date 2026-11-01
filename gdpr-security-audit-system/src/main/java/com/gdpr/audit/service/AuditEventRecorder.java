package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditEventRecorder {

    private final AuditChainService auditChainService;
    private final AuditOutboxPublisher outboxPublisher;

    @Transactional
    public AuditLog record(AuditLog event) {
        AuditLog saved = auditChainService.append(event);
        outboxPublisher.enqueue(saved);
        return saved;
    }
}