package com.aslp.order.strategy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Amazon SP-API 策略实现（配置真实凭据时启用）。
 *
 * <p>启用方式：{@code aslp.order.mock-enabled=false}。
 * 真实实现需完成 LWA OAuth 2.0 认证 + SP-API 签名请求；当前为骨架，
 * 未配置凭据时返回失败结果而不抛异常，保证流水线可观测。
 */
@Component
@ConditionalOnProperty(name = "aslp.order.mock-enabled", havingValue = "false")
public class AmazonSpApiStrategy implements OrderPullStrategy {

    @Override
    public String getPlatformName() {
        return "Amazon";
    }

    @Override
    public OrderPullResult pullOrders() {
        // TODO(M1)：接入 LWA OAuth 2.0 + SP-API /orders/v0/orders（含限流退避）
        return new OrderPullResult(
                "Amazon",
                List.of(),
                false,
                "No real Amazon SP-API credentials configured");
    }
}
