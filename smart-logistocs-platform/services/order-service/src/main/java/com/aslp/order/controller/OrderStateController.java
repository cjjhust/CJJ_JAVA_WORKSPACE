package com.aslp.order.controller;

import com.aslp.order.statemachine.OrderEvents;
import com.aslp.order.statemachine.OrderStateMachineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单状态机 REST 接口（M4 技术亮点：FBA 退货换标）。
 *
 * <p>完整状态流：
 * CREATED → PAID → PICKED → SHIPPED → FBA_RETURN_LABEL → FBA_RELABELED → DELIVERED → COMPLETED
 *
 * <p>状态按订单号隔离，避免多订单共享同一状态机实例。同时保留无参便捷端点，
 * 便于快速演示（默认订单 {@link OrderStateMachineService#DEFAULT_ORDER_ID}）。
 */
@RestController
@RequestMapping("/api/orders/state")
public class OrderStateController {

    private final OrderStateMachineService stateService;

    public OrderStateController(OrderStateMachineService stateService) {
        this.stateService = stateService;
    }

    /** 通用事件触发：{@code POST /api/orders/state/{orderId}/trigger?event=PAY}。 */
    @PostMapping("/{orderId}/trigger")
    public ResponseEntity<Map<String, Object>> trigger(
            @PathVariable String orderId,
            @RequestParam String event) {

        OrderEvents orderEvent;
        try {
            orderEvent = OrderEvents.valueOf(event.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "非法事件：" + event,
                    "allowed", Arrays.toString(OrderEvents.values())));
        }

        boolean ok = stateService.triggerEvent(orderId, orderEvent);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("event", orderEvent.name());
        body.put("success", ok);
        body.put("currentState", stateService.getCurrentState(orderId).name());
        return ResponseEntity.ok(body);
    }

    /** 查询指定订单当前状态。 */
    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> state(@PathVariable String orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("currentState", stateService.getCurrentState(orderId).name());
        body.put("trackedOrders", stateService.trackedOrders());
        return ResponseEntity.ok(body);
    }

    /** 重置指定订单状态机。 */
    @PostMapping("/{orderId}/reset")
    public ResponseEntity<Map<String, Object>> reset(@PathVariable String orderId) {
        stateService.reset(orderId);
        return ResponseEntity.ok(Map.of(
                "orderId", orderId,
                "currentState", stateService.getCurrentState(orderId).name()));
    }

    /** 便捷端点：对默认演示订单触发 FBA 退货。 */
    @PostMapping("/fba-return")
    public ResponseEntity<Map<String, Object>> triggerFbaReturn() {
        return trigger(OrderStateMachineService.DEFAULT_ORDER_ID, OrderEvents.FBA_RETURN.name());
    }

    /** 便捷端点：对默认演示订单触发换标完成。 */
    @PostMapping("/relabel")
    public ResponseEntity<Map<String, Object>> triggerRelabel() {
        return trigger(OrderStateMachineService.DEFAULT_ORDER_ID, OrderEvents.RELABEL.name());
    }
}
