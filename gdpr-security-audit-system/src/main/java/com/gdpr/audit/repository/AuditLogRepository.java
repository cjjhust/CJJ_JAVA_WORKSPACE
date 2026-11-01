package com.gdpr.audit.repository;

import com.gdpr.audit.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志 Repository — 提供 CRUD 与异常检测所需的查询。
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 按用户 ID 查询操作记录（最新在前） */
    List<AuditLog> findByUserIdOrderByTimestampDesc(String userId);

    /** 按操作类型 + 时间范围查询 */
    List<AuditLog> findByActionAndTimestampBetween(
            com.gdpr.audit.enums.AuditAction action,
            LocalDateTime start,
            LocalDateTime end);

    /** 按资源类型查询（最新在前） */
    List<AuditLog> findByResourceTypeOrderByTimestampDesc(String resourceType);

    List<AuditLog> findByChainSequenceIsNotNullOrderByChainSequenceAsc();

    /** 统计某用户在指定时间之后的操作次数（用于异常行为检测） */
    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.userId = :userId AND a.timestamp > :since")
    long countRecentActionsByUser(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /** 审计链最大序号（无链式记录时返回 null，用于启动一致性校验） */
    @Query("SELECT MAX(a.chainSequence) FROM AuditLog a")
    Long findMaxChainSequence();
}
