package com.aslp.order.statemachine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FBA 退货换标状态机单元测试（面试亮点：纯 Java 状态机 + 幂等/非法转换防护）。
 *
 * <p>状态流：CREATED → PAID → PICKED → SHIPPED → FBA_RETURN_LABEL → FBA_RELABELED → DELIVERED → COMPLETED
 */
class SimpleOrderStateMachineTest {

    @Test
    void happyPathWalksAllStates() {
        SimpleOrderStateMachine machine = new SimpleOrderStateMachine();

        assertEquals(OrderStates.CREATED, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.PAY));
        assertEquals(OrderStates.PAID, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.PICK));
        assertEquals(OrderStates.PICKED, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.SHIP));
        assertEquals(OrderStates.SHIPPED, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.FBA_RETURN));
        assertEquals(OrderStates.FBA_RETURN_LABEL, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.RELABEL));
        assertEquals(OrderStates.FBA_RELABELED, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.DELIVER));
        assertEquals(OrderStates.DELIVERED, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.COMPLETE));
        assertEquals(OrderStates.COMPLETED, machine.getCurrentState());
    }

    @Test
    void illegalTransitionIsRejectedAndStateUnchanged() {
        SimpleOrderStateMachine machine = new SimpleOrderStateMachine();

        // CREATED 状态下不允许直接发货
        assertFalse(machine.sendEvent(OrderEvents.SHIP));
        assertEquals(OrderStates.CREATED, machine.getCurrentState());
    }

    @Test
    void resetReturnsToCreated() {
        SimpleOrderStateMachine machine = new SimpleOrderStateMachine();
        machine.sendEvent(OrderEvents.PAY);
        machine.reset();
        assertEquals(OrderStates.CREATED, machine.getCurrentState());
    }

    @Test
    void canBeRestoredAtAPersistedState() {
        // P1-8：从数据库恢复状态时用这个构造 —— 恢复后必须能从"中间状态"继续推进，
        // 而不是回到 CREATED（否则持久化就白做了）
        SimpleOrderStateMachine machine = new SimpleOrderStateMachine(OrderStates.FBA_RETURN_LABEL);

        assertEquals(OrderStates.FBA_RETURN_LABEL, machine.getCurrentState());
        assertTrue(machine.sendEvent(OrderEvents.RELABEL));
        assertEquals(OrderStates.FBA_RELABELED, machine.getCurrentState());

        // 恢复后仍然遵守转换规则：FBA_RELABELED 不能直接 COMPLETE
        assertFalse(machine.sendEvent(OrderEvents.COMPLETE));
        assertEquals(OrderStates.FBA_RELABELED, machine.getCurrentState());
    }

    @Test
    void nullInitialStateFallsBackToCreated() {
        // 兜底：调用方传 null 不应抛 NPE（恢复路径上"没有历史状态"是正常情况）
        assertEquals(OrderStates.CREATED, new SimpleOrderStateMachine(null).getCurrentState());
    }
}
