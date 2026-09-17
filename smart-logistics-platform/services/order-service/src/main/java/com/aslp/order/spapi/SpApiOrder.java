package com.aslp.order.spapi;

import org.springframework.util.StringUtils;

/**
 * SP-API 订单的领域投影（P1-4）—— 由平台原报文映射而来，再经
 * {@link SpApiOrderMapper} 变成 M1 统一 DTO。
 *
 * <p>为什么要有这一层：平台报文（PascalCase、嵌套 payload）会随版本漂移，
 * 而业务只关心「单号 / 状态 / 目的地 / 商品名」四个语义字段；
 * 把漂移隔离在 spapi 包内，是「防腐层」的最小实现。
 *
 * @param orderId     平台订单号（AmazonOrderId，幂等键）
 * @param status      OrderStatus（SP-API 原样透传，如 Pending/Unshipped/Shipped）
 * @param city        收货城市（ShippingAddress.City，可能缺失）
 * @param countryCode 收货国家（ShippingAddress.CountryCode，可能缺失）
 * @param title       商品名（来自 /orderItems；未获取到时为 null）
 */
public record SpApiOrder(
        String orderId,
        String status,
        String city,
        String countryCode,
        String title
) {

    /**
     * 地址是否不完整。
     *
     * <p>对应 M1「异常物流单号自动打标」：收货城市或国家缺失时，后续履约无法路由，
     * 必须先由客服人工修正再进入流水线。
     */
    public boolean addressIncomplete() {
        return !StringUtils.hasText(city) || !StringUtils.hasText(countryCode);
    }

    /** 补充商品名（不可变记录的链式更新）。 */
    public SpApiOrder withTitle(String newTitle) {
        return new SpApiOrder(orderId, status, city, countryCode, newTitle);
    }
}
