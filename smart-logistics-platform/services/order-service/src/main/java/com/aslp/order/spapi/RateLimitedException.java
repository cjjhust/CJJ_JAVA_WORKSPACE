package com.aslp.order.spapi;

import com.aslp.order.strategy.PlatformUnavailableException;

/**
 * SP-API 限流（HTTP 429）。
 *
 * <p>继承 {@link PlatformUnavailableException} 是<b>有意为之</b>：P1-2 的 Resilience4j
 * 重试配置是按父类匹配的（{@code resilience4j.retry.instances.platformPull.retry-exceptions}），
 * 子类同样命中 —— 于是「429 属于可重试的暂时性故障」这条语义直接复用了既有容错链路，
 * 不需要再改一行配置。
 *
 * <p>额外携带 {@code Retry-After} 秒数，便于日志/探针回答「平台要求退避多久」。
 */
public class RateLimitedException extends PlatformUnavailableException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 平台在 Retry-After 头里要求的退避秒数（缺失时为 1）。 */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
