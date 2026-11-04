package com.aslp.inventory.event;

/**
 * P2-3 — topic 名称常量（与 order-service 的 {@code OrderEventPublisher} 保持一致；
 * 独立常量避免跨模块编译依赖——两个服务是独立部署单元，只共享契约字符串）。
 */
public final class OrderEventPublisherHolder {

    /** 订单同步事件 topic。 */
    public static final String TOPIC = "order.sync";

    /** 死信 topic（消费重试耗尽后落这里，等待重放）。 */
    public static final String DLT_TOPIC = "order.sync.DLT";

    private OrderEventPublisherHolder() {
    }
}
