package com.gdpr.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdpr.audit.annotation.Auditable;
import com.gdpr.audit.enums.AuditAction;
import com.gdpr.audit.service.AuditEventRecorder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 审计失败策略单测（fail-closed 分级开关）：
 * - 写操作 + 开关开：审计失败 → 业务异常传播（回滚）
 * - VIEW + 开关开：审计失败 → fail-open（返回 null，不阻断）
 * - 写操作 + 开关关：fail-open
 */
@ExtendWith(MockitoExtension.class)
class AuditAspectFailClosedTest {

    @Mock
    private AuditEventRecorder recorder;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager) {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                // 直接执行回调（无真实事务），让审计异常冒泡到切面的 catch
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        lenient().when(signature.getMethod()).thenReturn(auditedMethod());
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(joinPoint.getArgs()).thenReturn(new Object[0]);
        try {
            lenient().when(joinPoint.proceed()).thenReturn("ok");
        } catch (Throwable neverThrown) {
            throw new AssertionError(neverThrown);
        }
    }

    private AuditAspect aspect(boolean failClosed) {
        return new AuditAspect(recorder, new ObjectMapper(), transactionManager, failClosed) {
            @Override
            protected TransactionTemplate template() {
                return transactionTemplate;
            }
        };
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "User")
    private void deleteMethod() {
    }

    @Auditable(action = AuditAction.VIEW, resourceType = "User")
    private void viewMethod() {
    }

    private Method auditedMethod() {
        try {
            return AuditAspectFailClosedTest.class
                    .getDeclaredMethod("deleteMethod");
        } catch (NoSuchMethodException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void writeActionFailsClosedWhenEnabled() throws Throwable {
        doThrow(new IllegalStateException("audit down")).when(recorder).record(any());
        when(signature.getMethod()).thenReturn(auditedMethod());

        assertThatThrownBy(() -> aspect(true).audit(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit down");
    }

    @Test
    void viewActionStaysOpenEvenWhenEnabled() throws Throwable {
        doThrow(new IllegalStateException("audit down")).when(recorder).record(any());
        when(signature.getMethod())
                .thenReturn(AuditAspectFailClosedTest.class.getDeclaredMethod("viewMethod"));

        Object result = aspect(true).audit(joinPoint);

        // fail-open：不阻断查询，且必须返回业务原始结果（不能吞成 null）
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void writeActionStaysOpenWhenDisabled() throws Throwable {
        doThrow(new IllegalStateException("audit down")).when(recorder).record(any());
        when(signature.getMethod()).thenReturn(auditedMethod());

        Object result = aspect(false).audit(joinPoint);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void writeActionSucceedsWhenAuditWorks() throws Throwable {
        when(signature.getMethod()).thenReturn(auditedMethod());

        Object result = aspect(true).audit(joinPoint);

        assertThat(result).isEqualTo("ok");
    }
}
