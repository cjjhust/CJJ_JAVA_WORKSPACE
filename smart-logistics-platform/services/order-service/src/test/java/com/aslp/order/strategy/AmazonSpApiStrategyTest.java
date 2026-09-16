package com.aslp.order.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon SP-API 骨架策略测试。
 *
 * <p>当前实现未接入真实 LWA OAuth 2.0，契约是：<b>返回失败结果而不是抛异常</b>，
 * 这样 {@code POST /api/orders/pull} 仍能返回可观测的失败摘要（success=false + errorMsg），
 * 也不会被 Resilience4j 判定为平台故障而触发降级。
 */
class AmazonSpApiStrategyTest {

    private final AmazonSpApiStrategy strategy = new AmazonSpApiStrategy();

    @Test
    @DisplayName("平台标识为 Amazon（用于落库与日志区分）")
    void exposesPlatformName() {
        assertEquals("Amazon", strategy.getPlatformName());
    }

    @Test
    @DisplayName("未配置凭据：返回失败结果并给出原因，不抛异常")
    void returnsFailureResultWithoutCredentials() {
        OrderPullResult result = strategy.pullOrders();

        assertFalse(result.success());
        assertFalse(result.degraded(), "凭据缺失属业务性失败，不是容错降级");
        assertTrue(result.orders().isEmpty());
        assertEquals("Amazon", result.platform());
        assertNotNull(result.errorMsg());
        assertTrue(result.errorMsg().toLowerCase().contains("credential"),
                "错误信息需指向凭据缺失，便于运维定位");
    }

    @Test
    @DisplayName("可重复调用：无副作用、结果稳定")
    void isIdempotentForRepeatedCalls() {
        OrderPullResult first = strategy.pullOrders();
        OrderPullResult second = strategy.pullOrders();

        assertEquals(first.success(), second.success());
        assertEquals(first.errorMsg(), second.errorMsg());
    }
}
