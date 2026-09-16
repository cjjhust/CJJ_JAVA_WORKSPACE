package com.aslp.order.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-2 故障演练开关与 Mock 策略行为测试。
 *
 * <p>熔断/降级需要可控故障源才可验证，本测试锁定开关本身的行为边界。
 */
class MockAmazonStrategyTest {

    @Test
    @DisplayName("开关关闭（默认）：返回 3 条模拟订单，其中 1 条带异常标签")
    void returnsMockOrdersWhenSwitchOff() {
        MockAmazonStrategy strategy = new MockAmazonStrategy(new PlatformFailureSwitch());

        OrderPullResult result = strategy.pullOrders();

        assertTrue(result.success());
        assertFalse(result.degraded());
        assertEquals("Amazon-Mock", result.platform());
        assertEquals(3, result.orders().size());
        assertEquals(1, result.orders().stream().filter(order -> !order.isNormal()).count(),
                "AMZ-1003 带 ADDRESS_INVALID，用于验证异常打标链路");
    }

    @Test
    @DisplayName("开关打开：抛 PlatformUnavailableException，交由 Resilience4j 重试/熔断")
    void throwsPlatformUnavailableWhenSwitchOn() {
        PlatformFailureSwitch failureSwitch = new PlatformFailureSwitch();
        failureSwitch.setMode(PlatformFailureSwitch.Mode.ERROR);

        MockAmazonStrategy strategy = new MockAmazonStrategy(failureSwitch);

        PlatformUnavailableException ex =
                assertThrows(PlatformUnavailableException.class, strategy::pullOrders);
        assertTrue(ex.getMessage().contains("故障演练开关"));
        assertTrue(failureSwitch.isFailing());
    }

    @Test
    @DisplayName("开关可复原：ERROR -> NONE 后恢复正常返回")
    void canBeResetBackToNormal() {
        PlatformFailureSwitch failureSwitch = new PlatformFailureSwitch();
        failureSwitch.setMode(PlatformFailureSwitch.Mode.ERROR);
        failureSwitch.setMode(PlatformFailureSwitch.Mode.NONE);

        assertFalse(failureSwitch.isFailing());
        assertTrue(new MockAmazonStrategy(failureSwitch).pullOrders().success());
    }

    @Test
    @DisplayName("非法模式（null）回落到 NONE，避免演练开关被误置为失败态")
    void nullModeFallsBackToNone() {
        PlatformFailureSwitch failureSwitch = new PlatformFailureSwitch();

        failureSwitch.setMode(null);

        assertEquals(PlatformFailureSwitch.Mode.NONE, failureSwitch.getMode());
        assertFalse(failureSwitch.isFailing());
    }
}
