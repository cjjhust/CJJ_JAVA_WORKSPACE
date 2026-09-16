package com.aslp.order.service;

import com.aslp.order.strategy.OrderPullResult;
import com.aslp.order.strategy.OrderPullStrategy;
import com.aslp.order.strategy.PlatformUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-2 降级回退（fallback）单元测试。
 *
 * <p>注解式「重试 / 熔断 / 舱壁」依赖 Spring AOP 代理，属于集成范畴 ——
 * 其端到端行为由 {@code scripts/smoke-test.sh} 在容器内验证
 * （连续失败 -> 熔断打开 -> 返回降级响应 -> 自动恢复）。
 * 本测试锁定回退方法自身的契约：<b>任何异常都转成 degraded 结果，绝不向上抛</b>，
 * 这是「接口不返回 500」的最后一道保障。
 */
class PlatformPullGatewayTest {

    private static OrderPullStrategy strategyOf(OrderPullResult result) {
        return new OrderPullStrategy() {
            @Override
            public String getPlatformName() {
                return "Amazon-Mock";
            }

            @Override
            public OrderPullResult pullOrders() {
                return result;
            }
        };
    }

    @Test
    @DisplayName("正常调用：直接透传策略结果")
    void passesThroughSuccessfulResult() {
        PlatformPullGateway gateway = new PlatformPullGateway(
                strategyOf(new OrderPullResult("Amazon-Mock", List.of(), true, null)));

        OrderPullResult result = gateway.pull();

        assertTrue(result.success());
        assertFalse(result.degraded());
        assertEquals("Amazon-Mock", gateway.platformName());
    }

    @Test
    @DisplayName("回退：平台不可用 -> degraded 结果（不抛异常）")
    void fallbackReturnsDegradedResultForPlatformFailure() {
        PlatformPullGateway gateway = new PlatformPullGateway(strategyOf(null));

        OrderPullResult result = gateway.degraded(new PlatformUnavailableException("模拟平台故障"));

        assertFalse(result.success());
        assertTrue(result.degraded());
        assertEquals("Amazon-Mock", result.platform());
        assertTrue(result.orders().isEmpty());
        assertTrue(result.errorMsg().contains("PlatformUnavailableException"),
                "降级原因需保留异常类型，便于排查是重试耗尽还是熔断短路");
    }

    @Test
    @DisplayName("回退：熔断短路异常同样转 degraded（不区分异常来源）")
    void fallbackHandlesCircuitBreakerShortCircuit() {
        PlatformPullGateway gateway = new PlatformPullGateway(strategyOf(null));

        OrderPullResult result = gateway.degraded(new IllegalStateException("CircuitBreaker 'platformPull' is OPEN"));

        assertTrue(result.degraded());
        assertTrue(result.errorMsg().contains("CircuitBreaker"));
    }
}
