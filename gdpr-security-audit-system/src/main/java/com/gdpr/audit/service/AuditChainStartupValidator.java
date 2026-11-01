package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.repository.AuditChainHeadRepository;
import com.gdpr.audit.repository.AuditLogRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 审计链启动一致性校验（Task 38）。
 *
 * 应用启动时校验 audit_chain_heads.nextSequence 与 audit_logs 最大 chainSequence：
 * - 一致 → 正常模式
 * - 不一致（链头回退/数据被截断）→ 安全模式：拒绝追加审计事件，避免在新旧链
 *   交界处继续写入造成不可检测的断链；需人工介入修复后再重启。
 * - 缺失链头且已有链记录 → 同样视为不一致（链头被删除）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditChainStartupValidator implements ApplicationRunner {

    private final AuditChainHeadRepository headRepository;
    private final AuditLogRepository auditLogRepository;

    @Getter
    private volatile boolean safeMode = false;

    @Getter
    private volatile String inconsistencyReason;

    @Override
    public void run(ApplicationArguments args) {
        safeMode = !check();
        if (safeMode) {
            log.error("AUDIT CHAIN INCONSISTENT — entering SAFE MODE: audit appends are rejected. Reason: {}",
                    inconsistencyReason);
        } else {
            log.info("Audit chain startup check passed: head consistent with records");
        }
    }

    /**
     * @return true 表示一致（或合法的全新空库），false 表示不一致并已写入 reason。
     */
    boolean check() {
        Long maxSequence = auditLogRepository.findMaxChainSequence();
        AuditChainHead head = headRepository.findById(AuditChainService.STREAM_ID).orElse(null);

        if (head == null) {
            if (maxSequence == null) {
                // 全新空库：应用侧首次启动时由迁移/初始化建链头，这里放行
                return true;
            }
            inconsistencyReason = "chain head missing but " + maxSequence + " chained records exist";
            return false;
        }
        long expectedNext = (maxSequence == null ? 0 : maxSequence) + 1;
        if (head.getNextSequence() != expectedNext) {
            inconsistencyReason = "chain head nextSequence=" + head.getNextSequence()
                    + " but max record chainSequence=" + (maxSequence == null ? 0 : maxSequence);
            return false;
        }
        return true;
    }
}
