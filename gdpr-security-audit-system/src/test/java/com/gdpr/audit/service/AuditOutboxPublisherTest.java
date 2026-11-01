package com.gdpr.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.enums.AuditAction;
import com.gdpr.audit.repository.AuditOutboxRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;

/**
 * Outbox 投递器单测：入队写 PENDING、投递成功标记 SENT、超限标记 FAILED。
 */
@ExtendWith(MockitoExtension.class)
class AuditOutboxPublisherTest {

    @Mock
    private AuditOutboxRepository outboxRepository;

    @Mock
    private AuditOutboxClaimService claimService;

    private AuditOutboxPublisher publisher;
    private HttpServer elasticsearch;
    private AtomicInteger responseStatus;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() {
        publisher = new AuditOutboxPublisher(outboxRepository, claimService, new ObjectMapper());
        ReflectionTestUtils.setField(publisher, "batchSize", 100);
        ReflectionTestUtils.setField(publisher, "maxAttempts", 5);
        responseStatus = new AtomicInteger(201);
        requestCount = new AtomicInteger();
        try {
            elasticsearch = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            elasticsearch.createContext("/", exchange -> {
                requestCount.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus.get(), response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            elasticsearch.start();
            ReflectionTestUtils.setField(publisher, "elasticsearchUrl",
                    "http://127.0.0.1:" + elasticsearch.getAddress().getPort());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @AfterEach
    void stopElasticsearch() {
        elasticsearch.stop(0);
    }

    @Test
    void enqueuePersistsPendingEventWithPayload() {
        publisher.enqueue(AuditLog.builder()
            .id(42L)
            .timestamp(LocalDateTime.parse("2026-01-01T10:00:00"))
            .action(AuditAction.DELETE)
            .resourceType("User")
            .recordHash("record-hash")
            .build());

        ArgumentCaptor<AuditOutboxEvent> captor = ArgumentCaptor.forClass(AuditOutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        AuditOutboxEvent saved = captor.getValue();
        assertThat(saved.getAuditLogId()).isEqualTo(42L);
        assertThat(saved.getStatus()).isEqualTo(AuditOutboxEvent.OutboxStatus.PENDING);
        assertThat(saved.getPayload()).contains("\"auditLogId\":42", "\"severity\":\"HIGH\"");
    }

    @Test
    void relayMarksPendingEventsAsSent() {
        AuditOutboxEvent pending = AuditOutboxEvent.builder()
                .auditLogId(1L)
                .payload("{\"auditLogId\":1,\"timestamp\":\"2026-01-01T10:00:00\"}")
                .status(AuditOutboxEvent.OutboxStatus.PENDING)
                .attempts(0)
                .createdAt(java.time.Instant.now())
                .build();
        when(claimService.claimBatch(anyString(), anyInt(), any(), any()))
            .thenReturn(List.of(pending));
        when(outboxRepository.markSentIfOwned(any(), anyString(), any())).thenReturn(1);

        publisher.relay();

        assertThat(requestCount.get()).isEqualTo(1);
        verify(outboxRepository).markSentIfOwned(eq(pending.getId()), anyString(), any());
    }

    @Test
        void relayKeepsEventPendingWhenElasticsearchRejectsWrite() {
        AuditOutboxEvent retried = AuditOutboxEvent.builder()
                .auditLogId(2L)
            .payload("{\"auditLogId\":2,\"timestamp\":\"2026-01-01T10:00:00\"}")
                .status(AuditOutboxEvent.OutboxStatus.PENDING)
                .attempts(4)
                .createdAt(java.time.Instant.now())
                .build();
        responseStatus.set(503);
        when(claimService.claimBatch(anyString(), anyInt(), any(), any()))
            .thenReturn(List.of(retried));
        when(outboxRepository.releaseAfterFailure(any(), anyString(), anyInt(), any())).thenReturn(1);

        publisher.relay();

        verify(outboxRepository).releaseAfterFailure(eq(retried.getId()), anyString(), eq(5), any());
        }

        @Test
        void retriesPendingEventAfterElasticsearchRecovers() {
        AuditOutboxEvent event = AuditOutboxEvent.builder()
            .id(3L)
            .auditLogId(3L)
            .payload("{\"auditLogId\":3,\"timestamp\":\"2026-01-01T10:00:00\"}")
            .status(AuditOutboxEvent.OutboxStatus.PROCESSING)
            .attempts(1)
            .createdAt(java.time.Instant.now())
            .build();
        when(claimService.claimBatch(anyString(), anyInt(), any(), any()))
            .thenReturn(List.of(event), List.of(event));
        when(outboxRepository.releaseAfterFailure(any(), anyString(), anyInt(), any())).thenReturn(1);
        when(outboxRepository.markSentIfOwned(any(), anyString(), any())).thenReturn(1);

        responseStatus.set(503);
        publisher.relay();
        responseStatus.set(200);
        publisher.relay();

        assertThat(requestCount.get()).isEqualTo(2);
        verify(outboxRepository).releaseAfterFailure(eq(event.getId()), anyString(), eq(5), any());
        verify(outboxRepository).markSentIfOwned(eq(event.getId()), anyString(), any());
    }
}
