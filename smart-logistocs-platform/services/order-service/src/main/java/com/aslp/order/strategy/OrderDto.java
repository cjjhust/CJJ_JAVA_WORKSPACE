package com.aslp.order.strategy;

/**
 * 标准化订单 DTO —— 各电商平台异构报文的统一投影（M1）。
 *
 * @param orderId   平台订单号（全局唯一，幂等键）
 * @param product   商品描述
 * @param warehouse 履约仓库（Bruchsal / Moenchengladbach）
 * @param status    订单状态
 * @param errorTag  异常标签，{@code null} 表示数据正常
 */
public record OrderDto(
        String orderId,
        String product,
        String warehouse,
        String status,
        String errorTag
) {
    /** 兼容旧签名的便捷构造（无异常标签）。 */
    public OrderDto(String orderId, String product, String warehouse, String status) {
        this(orderId, product, warehouse, status, null);
    }

    /** 数据是否可直接进入履约流水线（无异常标签）。 */
    public boolean isNormal() {
        return errorTag == null || errorTag.isBlank();
    }
}
