package com.aslp.order.service;

import com.aslp.order.strategy.OrderPullResult;
import com.aslp.order.strategy.OrderPullStrategy;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P1-2 平台调用容错网关：把「重试 / 熔断 / 舱壁 / 降级回退」集中在这一层。
 *
 * <p><b>为什么必须单独抽一个 bean</b>：Resilience4j 的注解依赖 Spring AOP 代理生效，
 * 同类内部自调用（{@code this.xxx()}）会绕过代理 —— 即所谓"自调用陷阱"。
 * 因此必须由外部 bean 调用被注解的方法。
 *
 * <p><b>注解叠加顺序</b>（Resilience4j 固定的切面顺序，由外到内）：
 * <pre>
 *   Retry  ->  CircuitBreaker  ->  Bulkhead  ->  strategy.pullOrders()
 * </pre>
 * 含义：先用重试吸收平台抖动；连续失败后由熔断直接短路（避免把下游打死、也避免线程被拖住）；
 * 最内层舱壁限制并发，保护本服务线程池。
 *
 * <p><b>降级语义</b>：回退方法挂在<b>最外层</b>的 Retry 上 —— 这样"重试 3 次仍失败"
 * 才会产生降级结果；若把回退挂在 CircuitBreaker 上，异常会被内层吞掉，
 * 外层重试永远不会触发（常见配置错误）。任何失败都不向上抛异常，
 * 而是返回 {@code degraded=true} 的结果，让 {@code POST /api/orders/pull}
 * 返回 200 + 明确的降级标识，而不是 500。
 */
@Component
public class PlatformPullGateway {

    private static final Logger log = LoggerFactory.getLogger(PlatformPullGateway.class);

    private final OrderPullStrategy strategy;

    public PlatformPullGateway(OrderPullStrategy strategy) {
        this.strategy = strategy;
    }

    public String platformName() {
        return strategy.getPlatformName();
    }

    /** 受保护的平台拉取入口。 */
    @Retry(name = "platformPull", fallbackMethod = "degraded")
    @CircuitBreaker(name = "platformPull")
    @Bulkhead(name = "platformPull")
    public OrderPullResult pull() {
        return strategy.pullOrders();
    }

    /**
     * 降级回退（fallback）：重试耗尽 / 熔断打开（CallNotPermittedException）/ 舱壁拒绝时调用。
     *
     * <p>签名规则：与原方法一致，并在末尾额外接收异常参数。
     */
    OrderPullResult degraded(Throwable cause) {
        log.warn("[订单接入][降级] 平台 {} 调用失败，返回降级响应而不是抛异常：{}",
                strategy.getPlatformName(), cause.toString());
        return OrderPullResult.degraded(strategy.getPlatformName(),
                "平台调用失败已降级（" + cause.getClass().getSimpleName() + "）：" + cause.getMessage());
    }
}
