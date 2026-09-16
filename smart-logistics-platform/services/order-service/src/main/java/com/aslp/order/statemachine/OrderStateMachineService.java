package com.aslp.order.statemachine;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单状态机服务（M4 技术亮点：FBA 退货换标）。
 *
 * <p>历史问题：原实现持有单个 {@code SimpleOrderStateMachine} 实例，所有订单共享同一状态，
 * 并发场景下会互相污染（一次调用推进的是「全局」状态）。
 * 现改为 <b>按订单号隔离</b>的状态机实例，仅在内存中演示；
 * 生产化方案见 {@code todo.md} P2：状态持久化到 {@code orders.status} + 事件表。
 */
@Service
public class OrderStateMachineService {

    /** 默认订单号，便于无参调用（演示 / 冒烟测试）。 */
    public static final String DEFAULT_ORDER_ID = "DEMO-001";

    private final ConcurrentHashMap<String, SimpleOrderStateMachine> machines = new ConcurrentHashMap<>();

    private SimpleOrderStateMachine machineOf(String orderId) {
        return machines.computeIfAbsent(
                orderId == null || orderId.isBlank() ? DEFAULT_ORDER_ID : orderId,
                key -> new SimpleOrderStateMachine());
    }

    /** 触发状态迁移，返回是否发生合法转换。 */
    public boolean triggerEvent(String orderId, OrderEvents event) {
        return machineOf(orderId).sendEvent(event);
    }

    /** 便捷重载：使用默认订单号。 */
    public boolean triggerEvent(OrderEvents event) {
        return triggerEvent(DEFAULT_ORDER_ID, event);
    }

    public OrderStates getCurrentState(String orderId) {
        return machineOf(orderId).getCurrentState();
    }

    public OrderStates getCurrentState() {
        return getCurrentState(DEFAULT_ORDER_ID);
    }

    /** 重置指定订单的状态机（演示用）。 */
    public void reset(String orderId) {
        machineOf(orderId).reset();
    }

    /** 当前已跟踪的订单数量。 */
    public int trackedOrders() {
        return machines.size();
    }
}
