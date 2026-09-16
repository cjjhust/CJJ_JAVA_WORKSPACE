package com.aslp.order.statemachine;

import java.util.HashMap;
import java.util.Map;

/**
 * 纯 Java 实现的 FBA 退货换标状态机（避免 spring-statemachine 版本 API 不匹配）
 * 状态流：CREATED → PAID → PICKED → SHIPPED → FBA_RETURN_LABEL → FBA_RELABELED → DELIVERED → COMPLETED
 */
public class SimpleOrderStateMachine {

    private OrderStates currentState = OrderStates.CREATED;
    private final Map<OrderStates, Map<OrderEvents, OrderStates>> transitions = new HashMap<>();

    public SimpleOrderStateMachine() {
        // 定义状态转换规则
        addTransition(OrderStates.CREATED, OrderEvents.PAY, OrderStates.PAID);
        addTransition(OrderStates.PAID, OrderEvents.PICK, OrderStates.PICKED);
        addTransition(OrderStates.PICKED, OrderEvents.SHIP, OrderStates.SHIPPED);
        // FBA 退货换标核心流程
        addTransition(OrderStates.SHIPPED, OrderEvents.FBA_RETURN, OrderStates.FBA_RETURN_LABEL);
        addTransition(OrderStates.FBA_RETURN_LABEL, OrderEvents.RELABEL, OrderStates.FBA_RELABELED);
        addTransition(OrderStates.FBA_RELABELED, OrderEvents.DELIVER, OrderStates.DELIVERED);
        addTransition(OrderStates.DELIVERED, OrderEvents.COMPLETE, OrderStates.COMPLETED);
    }

    private void addTransition(OrderStates from, OrderEvents event, OrderStates to) {
        transitions.computeIfAbsent(from, k -> new HashMap<>()).put(event, to);
    }

    public boolean sendEvent(OrderEvents event) {
        Map<OrderEvents, OrderStates> fromTransitions = transitions.get(currentState);
        if (fromTransitions == null || !fromTransitions.containsKey(event)) {
            return false; // 无效转换
        }
        currentState = fromTransitions.get(event);
        return true;
    }

    public OrderStates getCurrentState() {
        return currentState;
    }

    public void reset() {
        currentState = OrderStates.CREATED;
    }
}
