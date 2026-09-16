package com.aslp.order.statemachine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机服务单元测试 —— 重点验证「按订单号隔离」这一修复。
 *
 * <p>历史缺陷：单实例状态机导致所有订单共享状态（A 订单推进会把 B 订单一起推进）。
 */
class OrderStateMachineServiceTest {

    private final OrderStateMachineService service = new OrderStateMachineService();

    @Test
    void statesAreIsolatedPerOrder() {
        // 推进 A 订单到 SHIPPED
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.PAY));
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.PICK));
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.SHIP));

        // B 订单必须仍在 CREATED，且不允许直接 FBA_RETURN
        assertEquals(OrderStates.CREATED, service.getCurrentState("ORDER-B"));
        assertFalse(service.triggerEvent("ORDER-B", OrderEvents.FBA_RETURN));

        // A 订单可以进入 FBA 退货换标
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.FBA_RETURN));
        assertEquals(OrderStates.FBA_RETURN_LABEL, service.getCurrentState("ORDER-A"));
        assertEquals(OrderStates.CREATED, service.getCurrentState("ORDER-B"));
    }

    @Test
    void fullFbaReturnFlowSucceeds() {
        String order = "ORDER-FBA";
        assertTrue(service.triggerEvent(order, OrderEvents.PAY));
        assertTrue(service.triggerEvent(order, OrderEvents.PICK));
        assertTrue(service.triggerEvent(order, OrderEvents.SHIP));
        assertTrue(service.triggerEvent(order, OrderEvents.FBA_RETURN));
        assertTrue(service.triggerEvent(order, OrderEvents.RELABEL));
        assertTrue(service.triggerEvent(order, OrderEvents.DELIVER));
        assertTrue(service.triggerEvent(order, OrderEvents.COMPLETE));

        assertEquals(OrderStates.COMPLETED, service.getCurrentState(order));
    }

    @Test
    void blankOrderIdFallsBackToDefault() {
        assertTrue(service.triggerEvent(null, OrderEvents.PAY));
        assertEquals(OrderStates.PAID, service.getCurrentState(OrderStateMachineService.DEFAULT_ORDER_ID));
    }

    @Test
    void resetReturnsToCreated() {
        service.triggerEvent("ORDER-R", OrderEvents.PAY);
        service.reset("ORDER-R");
        assertEquals(OrderStates.CREATED, service.getCurrentState("ORDER-R"));
    }
}
