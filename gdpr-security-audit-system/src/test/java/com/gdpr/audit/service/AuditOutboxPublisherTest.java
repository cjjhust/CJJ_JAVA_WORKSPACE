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
    private HttpServer elasticsearch;
    private AtomicInteger responseStatus;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() {
        publisher = new AuditOutboxPublisher(outboxRepository, new ObjectMapper());
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
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(
                any(AuditOutboxEvent.OutboxStatus.class), any(Pageable.class)))
                .thenReturn(List.of(pending));

        publisher.relay();

        assertThat(pending.getStatus()).isEqualTo(AuditOutboxEvent.OutboxStatus.SENT);
        assertThat(pending.getSentAt()).isNotNull();
        assertThat(requestCount.get()).isEqualTo(1);
        verify(outboxRepository).saveAll(List.of(pending));
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
        when(outboxRepository.findByStatusOrderByCreatedAtAsc(
                any(AuditOutboxEvent.OutboxStatus.class), any(Pageable.class)))
                .thenReturn(List.of(retried));

        publisher.relay();

        assertThat(retried.getAttempts()).isEqualTo(5);
        assertThat(retried.getStatus()).isEqualTo(AuditOutboxEvent.OutboxStatus.FAILED);
        assertThat(retried.getSentAt()).isNull();
    }
}
