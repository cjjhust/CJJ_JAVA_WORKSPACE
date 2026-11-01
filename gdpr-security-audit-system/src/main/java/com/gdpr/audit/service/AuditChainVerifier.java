package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.repository.AuditChainHeadRepository;
import com.gdpr.audit.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.List;

@Service
public class AuditChainVerifier {

    private final AuditLogRepository auditLogRepository;
    private final AuditChainHeadRepository headRepository;
    private final AuditChainService chainService;

    public AuditChainVerifier(AuditLogRepository auditLogRepository,
                              AuditChainHeadRepository headRepository,
                              AuditChainService chainService) {
        this.auditLogRepository = auditLogRepository;
        this.headRepository = headRepository;
        this.chainService = chainService;
    }

    @Transactional(readOnly = true)
    public AuditChainVerification verify() {
        List<AuditLog> records = auditLogRepository.findByChainSequenceIsNotNullOrderByChainSequenceAsc();
        long expectedSequence = 1;
        String expectedPreviousHash = AuditChainService.GENESIS_HASH;

        for (AuditLog record : records) {
            if (record.getChainSequence() == null || record.getChainSequence() != expectedSequence) {
                return invalid(record.getChainSequence(), expectedSequence, "sequence gap or duplicate", expectedPreviousHash);
            }
            if (!expectedPreviousHash.equals(record.getPreviousHash())) {
                return invalid(record.getChainSequence(), expectedSequence, "previous hash mismatch", expectedPreviousHash);
            }
            if (!chainService.verifyRecordHash(record)) {
                return invalid(record.getChainSequence(), expectedSequence, "record HMAC mismatch", expectedPreviousHash);
            }
            expectedPreviousHash = record.getRecordHash();
            expectedSequence++;
        }

        AuditChainHead head = headRepository.findById(AuditChainService.STREAM_ID).orElse(null);
        if (head == null) {
            return new AuditChainVerification(false, records.size(), expectedSequence, "chain head is missing", expectedPreviousHash);
        }
        if (head.getNextSequence() != expectedSequence || !head.getLastHash().equals(expectedPreviousHash)) {
            return new AuditChainVerification(false, records.size(), expectedSequence, "stored chain head does not match records", expectedPreviousHash);
        }
        return new AuditChainVerification(true, records.size(), null, "valid", expectedPreviousHash);
    }

    private AuditChainVerification invalid(Long sequence, long expectedSequence, String reason, String headHash) {
        return new AuditChainVerification(false, expectedSequence - 1, sequence, reason, headHash);
    }
}
