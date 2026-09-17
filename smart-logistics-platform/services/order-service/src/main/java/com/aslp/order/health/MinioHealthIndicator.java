package com.aslp.order.health;

import com.aslp.order.config.MinioProperties;
import com.aslp.order.service.DocumentStorageService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * P1-6：MinIO 健康指示器（{@code /actuator/health} 里的 {@code minio} 组件）。
 *
 * <p><b>为什么必须暴露</b>：单据是「对外凭证」，存储挂了意味着面单打不出来 ——
 * 这跟邮件（通知渠道，尽力而为）不是同一等级的依赖，必须有明确的健康信号，
 * 而不是等作业员发现打不了单。
 *
 * <p>details 里带桶名与对外端点：排查时第一个要确认的就是「服务在跟哪个 MinIO 说话、
 * 桶对不对」（本地/容器两套端点，最容易串）。
 */
@Component("minioHealthIndicator")
public class MinioHealthIndicator implements HealthIndicator {

    private final DocumentStorageService storage;
    private final MinioProperties properties;

    public MinioHealthIndicator(DocumentStorageService storage, MinioProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public Health health() {
        DocumentStorageService.BucketProbe probe = storage.probeBucket();
        Health.Builder builder = probe.available() ? Health.up() : Health.down();
        return builder
                .withDetail("bucket", properties.getBucket())
                .withDetail("detail", probe.detail())
                .withDetail("endpoint", properties.getEndpoint())
                .withDetail("presignEndpoint", properties.hasPublicEndpoint()
                        ? properties.getPublicEndpoint() : properties.getEndpoint())
                .withDetail("presignExpirySeconds", properties.getPresignExpiry().toSeconds())
                .build();
    }
}
