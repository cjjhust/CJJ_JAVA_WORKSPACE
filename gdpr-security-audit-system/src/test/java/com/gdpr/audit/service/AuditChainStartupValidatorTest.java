package com.gdpr.audit.service;

import com.gdpr.audit.entity.AuditChainHead;
import com.gdpr.audit.repository.AuditChainHeadRepository;
import com.gdpr.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 审计链启动一致性校验单测（Task 38）：
 * 一致放行、链头缺失/序号不匹配进入安全模式。
 */
@ExtendWith(MockitoExtension.class)
class AuditChainStartupValidatorTest {

    @Mock
    private AuditChainHeadRepository headRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    private AuditChainStartupValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AuditChainStartupValidator(headRepository, auditLogRepository);
    }

    private AuditChainHead head(long nextSequence) {
        return new AuditChainHead(AuditChainService.STREAM_ID, nextSequence,
                "0".repeat(64), Instant.now(), 0);
    }

    @Test
    void consistentHeadPasses() {
        when(auditLogRepository.findMaxChainSequence()).thenReturn(7L);
        when(headRepository.findById(AuditChainService.STREAM_ID))
                .thenReturn(Optional.of(head(8L)));

        assertThat(validator.check()).isTrue();
        assertThat(validator.isSafeMode()).isFalse();
    }

    @Test
    void emptyDatabasePasses() {
        when(auditLogRepository.findMaxChainSequence()).thenReturn(null);
        when(headRepository.findById(AuditChainService.STREAM_ID)).thenReturn(Optional.empty());

        assertThat(validator.check()).isTrue();
    }

    @Test
    void missingHeadWithRecordsFails() {
        when(auditLogRepository.findMaxChainSequence()).thenReturn(5L);
        when(headRepository.findById(AuditChainService.STREAM_ID)).thenReturn(Optional.empty());

        assertThat(validator.check()).isFalse();
        assertThat(validator.getInconsistencyReason()).contains("chain head missing");
    }

    @Test
    void sequenceMismatchFails() {
        when(auditLogRepository.findMaxChainSequence()).thenReturn(5L);
        // 链头回退（nextSequence=3 < 6）→ 不一致
        when(headRepository.findById(AuditChainService.STREAM_ID))
                .thenReturn(Optional.of(head(3L)));

        assertThat(validator.check()).isFalse();
        assertThat(validator.getInconsistencyReason()).contains("nextSequence=3");
    }

    @Test
    void runSetsSafeModeOnInconsistency() {
        when(auditLogRepository.findMaxChainSequence()).thenReturn(5L);
        when(headRepository.findById(AuditChainService.STREAM_ID))
                .thenReturn(Optional.of(head(3L)));

        validator.run(null);

        assertThat(validator.isSafeMode()).isTrue();
    }
}
