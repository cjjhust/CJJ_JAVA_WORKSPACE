package com.aslp.order.strategy;

/**
 * 电商平台订单拉取策略接口（策略模式，M1）。
 *
 * <p>把 Amazon / eBay 等平台「认证机制、报文格式、限流策略」的异构性封装在实现类内部，
 * 对外暴露统一的 {@link #pullOrders()}。无真实账号时切换到 Mock 实现，保证流水线可测。
 */
public interface OrderPullStrategy {

    /** 平台标识，用于落库与日志区分。 */
    String getPlatformName();

    /** 执行一次全量/增量拉取。实现类必须自行吞掉平台侧异常并写入 {@code errorMsg}。 */
    OrderPullResult pullOrders();
}
