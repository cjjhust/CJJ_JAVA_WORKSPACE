package com.aslp.route.tracking;

/**
 * 承运商接口不可用（M3 追踪）。
 *
 * <p>可重试语义：5xx / 429 / 连接超时 / 读取超时都归到这里 —— 都是"对方或网络的问题，
 * 等一会儿再来一次可能就好了"。对外映射为 HTTP <b>503</b>（依赖故障，可重试）。
 *
 * <p>与 {@link TrackingClientException} 的分界线是「谁的错」：
 * 这条线决定了调用方应该"重试"还是"改请求"。
 */
public class TrackingUnavailableException extends RuntimeException {

    private final Carrier carrier;

    /** 是否命中上游限流（429）—— 处置不同：限流要退避，服务不可用要看对方状态页。 */
    private final boolean rateLimited;

    /** 上游 Retry-After（秒），没有则为 null。 */
    private final Integer retryAfterSeconds;

    public TrackingUnavailableException(Carrier carrier, String message, Throwable cause,
                                        boolean rateLimited, Integer retryAfterSeconds) {
        super(message, cause);
        this.carrier = carrier;
        this.rateLimited = rateLimited;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
