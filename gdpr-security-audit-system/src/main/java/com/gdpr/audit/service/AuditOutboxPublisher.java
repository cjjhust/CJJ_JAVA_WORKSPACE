package com.gdpr.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.gdpr.audit.entity.AuditLog;
import com.gdpr.audit.entity.AuditOutboxEvent;
import com.gdpr.audit.repository.AuditOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbox 投递器 — 周期性将 PENDING 审计事件写入 Elasticsearch。
 *
 * ES 使用 auditLogId 作为文档 ID；重复 PUT 幂等覆盖同一文档。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditOutboxPublisher {

    private final AuditOutboxRepository outboxRepository;
    private final AuditOutboxClaimService claimService;
    private final ObjectMapper objectMapper;
    private final String workerId = AuditOutboxClaimService.newOwnerId();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${audit.outbox.elasticsearch-url:http://localhost:9200}")
    private String elasticsearchUrl;

    @Value("${audit.outbox.index-prefix:audit-logs}")
    private String indexPrefix;

    @Value("${audit.outbox.batch-size:100}")
    private int batchSize;

    @Value("${audit.outbox.max-attempts:5}")
    private int maxAttempts;

    @Value("${audit.outbox.lease-seconds:900}")
    private int leaseSeconds;

    /**
     * 将完整审计事件写入 Outbox；调用方应在保存链记录的同一事务中调用。
     */
    @Transactional
    @SuppressWarnings("null")
    public void enqueue(AuditLog auditLog) {
        try {
            Map<String, Object> payload = toPayload(auditLog);
            payload.put("auditLogId", auditLog.getId());
            payload.put("severity", severityFor(auditLog));
            payload.put("environment", "development");
            AuditOutboxEvent outboxEvent = AuditOutboxEvent.builder()
                    .auditLogId(auditLog.getId())
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(AuditOutboxEvent.OutboxStatus.PENDING)
                    .attempts(0)
                    .createdAt(Instant.now())
                    .build();
                outboxRepository.save(outboxEvent);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to enqueue audit event " + auditLog.getId(), ex);
        }
    }

    /**
        * 周期投递：ES 返回 2xx 后标记 SENT；失败则保留 PENDING 并计数，达到上限后进入 FAILED。
     */
    @Scheduled(fixedDelayString = "${audit.outbox.poll-interval-ms:1000}")
    public void relay() {
        List<AuditOutboxEvent> claimed = claimService.claimBatch(
                workerId, batchSize, Instant.now(), Duration.ofSeconds(leaseSeconds));
        for (AuditOutboxEvent event : claimed) {
            try {
                sendToElasticsearch(event);
                int updated = outboxRepository.markSentIfOwned(event.getId(), workerId, Instant.now());
                if (updated == 0) {
                    log.warn("Outbox lease lost before completing event {}", event.getId());
                }
            } catch (Exception ex) {
                int updated = outboxRepository.releaseAfterFailure(event.getId(), workerId, maxAttempts,
                    Instant.now().plusSeconds(retryDelaySeconds(event.getAttempts() + 1)));
                log.warn("Outbox relay attempt {} failed for event {}: {}",
                        event.getAttempts() + 1, event.getId(), ex.getMessage());
                if (updated == 0) {
                    log.warn("Outbox lease lost before recording failure for event {}", event.getId());
                }
            }
        }
    }

    /**
     * 清理已投递事件（保留 7 天），避免 Outbox 表无限膨胀。
     */
    @Scheduled(cron = "${audit.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void cleanup() {
        int removed = outboxRepository.deleteSentBefore(Instant.now().minusSeconds(7 * 24 * 3600));
        if (removed > 0) {
            log.info("Cleaned {} sent outbox events", removed);
        }
    }

    private void sendToElasticsearch(AuditOutboxEvent event) throws IOException, InterruptedException {
        JsonNode payload = objectMapper.readTree(event.getPayload());
        JsonNode timestamp = payload.get("timestamp");
        Instant eventTime = timestamp != null && timestamp.isTextual()
            ? java.time.LocalDateTime.parse(timestamp.asText()).toInstant(ZoneOffset.UTC)
                : Instant.now();
        String index = indexPrefix + "-" + DateTimeFormatter.ofPattern("yyyy.MM.dd")
                .withZone(ZoneOffset.UTC).format(eventTime);
        URI target = URI.create(elasticsearchUrl.replaceAll("/$", "") + "/"
                + index + "/_doc/" + event.getAuditLogId());
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(event.getPayload()))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Elasticsearch returned HTTP " + response.statusCode());
        }
    }

    private String severityFor(AuditLog event) {
        if (event.getAction() == null) {
            return "LOW";
        }
        return switch (event.getAction()) {
            case DELETE, EXPORT, DOWNLOAD -> "HIGH";
            case MODIFY -> "MEDIUM";
            default -> "LOW";
        };
    }

    private long retryDelaySeconds(int attempt) {
        return Math.min(1L << Math.min(attempt, 8), 300L);
    }

    private Map<String, Object> toPayload(AuditLog event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", event.getTimestamp() == null ? null : event.getTimestamp().toString());
        payload.put("userId", event.getUserId());
        payload.put("username", event.getUsername());
        payload.put("userRoles", event.getUserRoles());
        payload.put("action", event.getAction() == null ? null : event.getAction().name());
        payload.put("resourceType", event.getResourceType());
        payload.put("resourceId", event.getResourceId());
        payload.put("requestMethod", event.getRequestMethod());
        payload.put("requestUri", event.getRequestUri());
        payload.put("requestBody", event.getRequestBody());
        payload.put("responseStatus", event.getResponseStatus());
        payload.put("clientIp", event.getClientIp());
        payload.put("userAgent", event.getUserAgent());
        payload.put("duration", event.getDuration());
        payload.put("success", event.getSuccess());
        payload.put("errorMessage", event.getErrorMessage());
        payload.put("chainSequence", event.getChainSequence());
        payload.put("previousHash", event.getPreviousHash());
        payload.put("recordHash", event.getRecordHash());
        payload.put("signingKeyId", event.getSigningKeyId());
        return payload;
    }
}
