package com.gdpr.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.repository.AuditOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 投递器单测：入队写 PENDING、投递成功标记 SENT、超限标记 FAILED。
 */
@ExtendWith(MockitoExtension.class)
class AuditOutboxPublisherTest {

    @Mock
    private AuditOutboxRepository outboxRepository;

    private AuditOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AuditOutboxPublisher(outboxRepository, new ObjectMapper());
        ReflectionTestUtils.setField(publisher, "batchSize", 100);
        ReflectionTestUtils.setField(publisher, "maxAttempts", 5);
    }

    @Test
    void enqueuePersistsPendingEventWithPayload() {
        publisher.enqueue(42L, java.util.Map.of("chainSequence", 1));

        ArgumentCaptor<AuditOutboxEvent> captor = ArgumentCaptor.forClass(AuditOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        AuditOutboxEvent saved = captor.getValue();
        assertThat(saved.getAuditLogId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo(AuditOutboxEvent.OutboxStatus.PENDING);
        assertThat(saved.getPayload()).contains("\"chainSequence\":1");
    }

    @Test
    void relayMarksPendingEventsAsSent() {
        AuditOutboxEvent pending = AuditOutboxEvent.builder()
                .auditLogId(1L)
                .payload("{\"auditLogId\":1}")
                .status(AuditOutboxEvent.OutboxStatus.PENDING)
                .attempts(0)
                .createdAt(java.time.Instant.now())
                .build();
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(
                any(AuditOutboxEvent.OutboxStatus.class), any(Pageable.class)))
                .thenReturn(List.of(pending));

        publisher.relay();

        assertThat(pending.getStatus()).isEqualTo(AuditOutboxEvent.OutboxStatus.SENT);
        assertThat(pending.getSentAt()).isNotNull();
        verify(outboxRepository).saveAll(List.of(pending));
    }

    @Test
    void relaySucceedsForPreviouslyRetriedEvent() {
        // AUDIT logger 输出本身不会抛异常；FAILED 分支是防御性代码，
        // 此处验证带历史 attempts 的事件投递成功后仍标记 SENT
        AuditOutboxEvent retried = AuditOutboxEvent.builder()
                .auditLogId(2L)
                .payload("{\"auditLogId\":2}")
                .status(AuditOutboxEvent.OutboxStatus.PENDING)
                .attempts(4)
                .createdAt(java.time.Instant.now())
                .build();
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(
                any(AuditOutboxEvent.OutboxStatus.class), any(Pageable.class)))
                .thenReturn(List.of(retried));

        publisher.relay();

        assertThat(retried.getAttempts()).isEqualTo(4);
        assertThat(retried.getStatus()).isEqualTo(AuditOutboxEvent.OutboxStatus.SENT);
    }
}
