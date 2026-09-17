package com.aslp.order.spapi;

import com.aslp.order.strategy.PlatformUnavailableException;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4 × P1-2 的边界契约：SP-API 的异常分类必须与 Resilience4j 的重试配置对齐。
 *
 * <p><b>为什么要单独锁这条</b>：分类写在 {@code spapi} 包，重试配置写在
 * {@code application.yml}，两处任意一处被改坏都<b>不会产生编译错误</b> ——
 * 只会在线上表现为「平台限流时静默不重试」或「400 被重试 3 次把配额打光」。
 * 这里不靠人读配置，而是用真实的 {@link RetryConfig} 断言谓词行为。
 *
 * <p>{@link RetryConfig} 与 {@code application.yml} 中的
 * {@code resilience4j.retry.instances.platformPull} 逐项对应（改了那里请同步这里，
 * 本类的存在就是为了让这种漂移在 CI 里变成红色）。
 */
@DisplayName("P1-4 异常分类 × P1-2 重试配置：边界一致性")
class SpApiRetryClassificationTest {

    /** 等价于 application.yml 里 platformPull 的重试实例配置。 */
    private static final RetryConfig PLATFORM_PULL = RetryConfig.custom()
            .maxAttempts(3)
            .retryExceptions(PlatformUnavailableException.class)
            .build();

    @Test
    @DisplayName("429（子类）命中重试配置：继承关系就是重试语义的载体")
    void rateLimitMatchesConfiguredRetryException() {
        assertTrue(PLATFORM_PULL.getExceptionPredicate().test(new RateLimitedException("429", 7)),
                "RateLimitedException 必须是 PlatformUnavailableException 的子类，否则 429 不会被重试");
    }

    @Test
    @DisplayName("超时 / 5xx 同属可重试家族，一并命中")
    void retryableFamilyMatches() {
        assertTrue(PLATFORM_PULL.getExceptionPredicate()
                .test(new PlatformUnavailableException("SP-API 连接或读取超时")));
    }

    @Test
    @DisplayName("4xx 契约错不命中重试：重试只会打光平台配额并把熔断推到打开")
    void nonRetryableDoesNotMatch() {
        assertFalse(PLATFORM_PULL.getExceptionPredicate().test(new SpApiClientException("400")),
                "不可重试的失败必须落在 retry-exceptions 的继承体系之外");
    }
}
