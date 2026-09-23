package com.aslp.order.statemachine;

import java.util.HashMap;
import java.util.Map;

/**
 * 纯 Java 实现的 FBA 退货换标状态机（避免 spring-statemachine 版本 API 不匹配）
 * 状态流：CREATED → PAID → PICKED → SHIPPED → FBA_RETURN_LABEL → FBA_RELABELED → DELIVERED → COMPLETED
 *
 * <p><b>它只负责「转换规则」，不负责「状态存在哪」</b>：本类无状态持久化、无 Spring 依赖，
 * 因此可以在纯单测里秒级验证全部转换路径。状态的存储与并发保护由
 * {@link OrderStateMachineService} 负责（DB 为唯一真相源）。
 *
 * <p>无参构造 = 从 CREATED 开始；带初始状态的构造用于**从数据库恢复**
 * （服务重启后继续推进同一订单，而不是回到起点）。
 */
public class SimpleOrderStateMachine {

    private OrderStates currentState;
    private final Map<OrderStates, Map<OrderEvents, OrderStates>> transitions = new HashMap<>();

    public SimpleOrderStateMachine() {
        this(OrderStates.CREATED);
    }

    /**
     * 从指定状态开始（用于恢复已持久化的状态）。
     *
     * <p>刻意不做「该状态是否可达」的校验：可达性由数据库里的既有事实决定，
     * 这里再判一次只会让恢复逻辑多一条失败路径。非法状态值会在构造参数类型上就被拦住（枚举）。
     */
    public SimpleOrderStateMachine(OrderStates initialState) {
        this.currentState = initialState == null ? OrderStates.CREATED : initialState;
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
