package com.gdpr.audit.controller;

import com.gdpr.audit.service.AuditChainVerification;
import com.gdpr.audit.service.AuditChainVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-chain")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditIntegrityController {

    private final AuditChainVerifier auditChainVerifier;

    @GetMapping("/verify")
    public AuditChainVerification verify() {
        return auditChainVerifier.verify();
    }
}
