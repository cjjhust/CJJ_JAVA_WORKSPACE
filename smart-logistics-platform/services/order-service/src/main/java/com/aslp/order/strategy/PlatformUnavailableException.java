package com.aslp.order.strategy;

/**
 * 平台侧不可用：超时 / 429 限流 / 5xx / 网络抖动。
 *
 * <p>P1-2 引入。策略实现类抛出该异常即表示"这次失败值得重试"，
 * 由 {@code PlatformPullGateway} 上的 Resilience4j 重试/熔断/舱壁统一处理；
 * 重试耗尽或熔断打开后会走降级回退，保证上游拿到的是降级响应而不是 500。
 */
public class PlatformUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlatformUnavailableException(String message) {
        super(message);
    }
}
