package com.gdpr.audit.service;

public record AuditChainVerification(
        boolean valid,
        long verifiedRecords,
        Long firstInvalidSequence,
        String reason,
        String verifiedHeadHash) {
}
