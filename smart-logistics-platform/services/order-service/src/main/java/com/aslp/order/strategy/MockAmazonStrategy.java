package com.aslp.order.strategy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mock Amazon 策略：无真实 SP-API 账号时使用，生成模拟订单数据用于流水线测试。
 *
 * <p>通过 {@code aslp.order.mock-enabled=false} 可切换到 {@link AmazonSpApiStrategy}。
 * 其中第三条数据刻意带上地址异常，用于验证 M1「异常物流单号自动打标」链路。
 *
 * <p>P1-2：支持故障注入（{@link PlatformFailureSwitch}）—— 开关打开时抛
 * {@link PlatformUnavailableException}，用于演练「连续失败 -> 熔断打开 -> 降级响应」。
 */
@Component
@ConditionalOnProperty(name = "aslp.order.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockAmazonStrategy implements OrderPullStrategy {

    private final PlatformFailureSwitch failureSwitch;

    public MockAmazonStrategy(PlatformFailureSwitch failureSwitch) {
        this.failureSwitch = failureSwitch;
    }

    @Override
    public String getPlatformName() {
        return "Amazon-Mock";
    }

    @Override
    public OrderPullResult pullOrders() {
        if (failureSwitch.isFailing()) {
            // P1-2 演练：模拟平台不可用（超时 / 429 限流 / 5xx）
            throw new PlatformUnavailableException("Amazon-Mock 模拟平台故障（故障演练开关已打开）");
        }
        // 模拟 Amazon.de 订单：奶粉一件代发 / 大件中转 / 地址不合规（待客服修正）
        return new OrderPullResult("Amazon-Mock", List.of(
                new OrderDto("AMZ-1001", "奶粉一件代发(6罐装)", "Bruchsal", "PAID", null),
                new OrderDto("AMZ-1002", "大件中转-婴儿推车", "Moenchengladbach", "PAID", null),
                new OrderDto("AMZ-1003", "逆向退货换标", "Bruchsal", "CREATED", "ADDRESS_INVALID")
        ), true, null);
    }
}
