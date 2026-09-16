package com.aslp.order.strategy;

import java.util.List;

/**
 * 平台拉取结果。
 *
 * <p>P1-2 新增 {@code degraded}：标识这是**降级结果** —— 平台调用被重试耗尽 / 熔断打开 /
 * 舱壁拒绝，由 {@link com.aslp.order.service.PlatformPullGateway} 的回退方法合成。
 * 上游据此返回「降级响应」（HTTP 200 + degraded=true）而不是 500。
 */
public record OrderPullResult(
        String platform,
        List<OrderDto> orders,
        boolean success,
        String errorMsg,
        boolean degraded) {

    /** 兼容构造：普通（非降级）结果。 */
    public OrderPullResult(String platform, List<OrderDto> orders, boolean success, String errorMsg) {
        this(platform, orders, success, errorMsg, false);
    }

    /** 降级结果工厂：无订单、success=false、degraded=true。 */
    public static OrderPullResult degraded(String platform, String errorMsg) {
        return new OrderPullResult(platform, List.of(), false, errorMsg, true);
    }
}
