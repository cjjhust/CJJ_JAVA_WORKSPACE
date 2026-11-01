package com.gdpr.audit.entity;

import com.gdpr.audit.enums.AuditAction;
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
import jakarta.persistence.PrePersist;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 审计日志实体 — 持久化到 PostgreSQL 的 audit_logs 表。
 *
 * 记录一次敏感操作的完整上下文：操作人（从 JWT 提取）、操作类型、
 * 目标资源、HTTP 请求信息、响应状态、耗时与脱敏后的请求体/错误信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "audit_logs",
    indexes = {
        @Index(name = "idx_audit_user_time", columnList = "userId, timestamp"),
        @Index(name = "idx_audit_action_time", columnList = "action, timestamp")
    }
)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作时间；保留链服务传入的规范化时间，仅在缺失时补值 */
    @Column(nullable = false, updatable = false)
    private LocalDateTime timestamp;

    /** 操作人 ID（JWT sub） */
    @Column(name = "user_id", nullable = false)
    private String userId;

    /** 操作人用户名（JWT preferred_username） */
    @Column(nullable = false)
    private String username;

    /** 操作人角色，逗号分隔 */
    @Column(name = "user_roles")
    private String userRoles;

    /** 操作类型 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    /** 资源类型（如 User、Document、Report） */
    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    /** 被操作资源的 ID */
    @Column(name = "resource_id")
    private String resourceId;

    /** HTTP 方法（GET/POST/PUT/DELETE） */
    @Column(name = "request_method")
    private String requestMethod;

    /** 请求路径 */
    @Column(name = "request_uri")
    private String requestUri;

    /** 请求体（已脱敏） */
    @Column(name = "request_body", columnDefinition = "TEXT")
    private String requestBody;

    /** HTTP 响应状态码 */
    @Column(name = "response_status")
    private Integer responseStatus;

    /** 客户端 IP */
    @Column(name = "client_ip")
    private String clientIp;

    /** 客户端 User-Agent */
    @Column(name = "user_agent")
    private String userAgent;

    /** 方法执行耗时（毫秒） */
    @Column
    private Long duration;

    /** 操作是否成功 */
    @Column
    private Boolean success;

    /** 失败时的错误信息（已脱敏） */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "chain_sequence", unique = true)
    private Long chainSequence;

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "record_hash", length = 64)
    private String recordHash;

    @Column(name = "signing_key_id", length = 64)
    private String signingKeyId;

    @PrePersist
    private void initializeTimestamp() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
    }
}
