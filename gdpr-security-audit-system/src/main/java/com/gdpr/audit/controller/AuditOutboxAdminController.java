package com.gdpr.audit.controller;

import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.repository.AuditOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Outbox 死信管理端点（Task 39）。
 *
 * - GET  /api/audit/outbox/failed  — ROLE_AUDITOR 查询投递失败事件
 * - POST /api/admin/outbox/{id}/replay — ROLE_ADMIN 将 FAILED 事件重置为 PENDING 回放
 *
 * 回放语义：事件 payload 原样重新投递；ES 侧按 document_id=auditLogId 幂等去重，
 * 重复投递不产生重复文档。
 */
@RestController
@RequiredArgsConstructor
public class AuditOutboxAdminController {

    private final AuditOutboxRepository outboxRepository;

    @GetMapping("/api/audit/outbox/failed")
    @PreAuthorize("hasRole('AUDITOR')")
    public java.util.List<AuditOutboxEvent> failed(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                                   @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return outboxRepository.findByStatusOrderByCreatedAtAsc(
                AuditOutboxEvent.OutboxStatus.FAILED,
                PageRequest.of(page, Math.min(size, 100), Sort.by("createdAt").ascending()));
    }

    @PostMapping("/api/admin/outbox/{id}/replay")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @SuppressWarnings("null")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable Long id) {
        AuditOutboxEvent event = outboxRepository.findById(id).orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }
        if (event.getStatus() != AuditOutboxEvent.OutboxStatus.FAILED) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "only FAILED events can be replayed",
                    "currentStatus", event.getStatus().name()));
        }
        if (outboxRepository.replayFailed(id) == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "event changed before replay"));
        }
        return ResponseEntity.ok(Map.of(
                "replayed", id,
                "status", "PENDING",
                "replayedAt", Instant.now().toString()));
    }
}
