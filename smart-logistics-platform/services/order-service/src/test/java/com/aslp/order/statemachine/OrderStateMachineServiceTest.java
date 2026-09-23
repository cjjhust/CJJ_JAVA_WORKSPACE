package com.aslp.order.statemachine;

import com.aslp.order.entity.OrderStateEventRecord;
import com.aslp.order.repository.OrderStateEventRepository;
import com.aslp.order.repository.OrderStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机持久化测试（P1-8）—— 用**真实 JPA + 内存库**验证，而不是 Mock 仓储。
 *
 * <p><b>为什么必须真写库</b>：本轮要证明的命题是「服务重启后状态还在」。
 * 若用 Mockito 打桩仓储，只能验证到"我调用了 save"，而这恰恰不是会出问题的地方 ——
 * 真正的风险在实体映射、事务边界与"状态到底存在哪"。
 *
 * <p><b>怎么模拟「重启」</b>：新建一个 {@link OrderStateMachineService} 实例（进程内状态归零），
 * 让它读同一个库。旧实现（ConcurrentHashMap）在这个测试下必然失败 —— 这就是回归证据。
 *
 * <p>H2 建表由实体生成（{@code ddl-auto=create-drop}）并关掉 Flyway：
 * 迁移脚本是 PostgreSQL 方言（TIMESTAMPTZ / BIGSERIAL / COMMENT ON），不该在测试库里跑。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(OrderStateMachineService.class)
class OrderStateMachineServiceTest {

    @Autowired
    private OrderStateRepository stateRepository;

    @Autowired
    private OrderStateEventRepository eventRepository;

    @Autowired
    private OrderStateMachineService service;

    /** 模拟「服务重启」：同一套仓储，全新的 service 实例（进程内没有任何缓存可依赖）。 */
    private OrderStateMachineService afterRestart() {
        return new OrderStateMachineService(stateRepository, eventRepository);
    }

    @Test
    @DisplayName("按订单号隔离：推进 A 不影响 B")
    void statesAreIsolatedPerOrder() {
        // 推进 A 订单到 SHIPPED
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.PAY).accepted());
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.PICK).accepted());
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.SHIP).accepted());

        // B 订单必须仍在 CREATED，且不允许直接 FBA_RETURN
        assertEquals(OrderStates.CREATED, service.getCurrentState("ORDER-B"));
        assertFalse(service.triggerEvent("ORDER-B", OrderEvents.FBA_RETURN).accepted());

        // A 订单可以进入 FBA 退货换标
        assertTrue(service.triggerEvent("ORDER-A", OrderEvents.FBA_RETURN).accepted());
        assertEquals(OrderStates.FBA_RETURN_LABEL, service.getCurrentState("ORDER-A"));
        assertEquals(OrderStates.CREATED, service.getCurrentState("ORDER-B"));
    }

    @Test
    void fullFbaReturnFlowSucceeds() {
        String order = "ORDER-FBA";
        assertTrue(service.triggerEvent(order, OrderEvents.PAY).accepted());
        assertTrue(service.triggerEvent(order, OrderEvents.PICK).accepted());
        assertTrue(service.triggerEvent(order, OrderEvents.SHIP).accepted());
        assertTrue(service.triggerEvent(order, OrderEvents.FBA_RETURN).accepted());
        assertTrue(service.triggerEvent(order, OrderEvents.RELABEL).accepted());
        assertTrue(service.triggerEvent(order, OrderEvents.DELIVER).accepted());
        assertTrue(service.triggerEvent(order, OrderEvents.COMPLETE).accepted());

        assertEquals(OrderStates.COMPLETED, service.getCurrentState(order));
    }

    @Test
    @DisplayName("核心命题：重启后状态不丢（旧的内存实现必然失败）")
    void stateSurvivesServiceRestart() {
        String order = "ORDER-RESTART";
        service.triggerEvent(order, OrderEvents.PAY);
        service.triggerEvent(order, OrderEvents.PICK);
        service.triggerEvent(order, OrderEvents.SHIP);
        service.triggerEvent(order, OrderEvents.FBA_RETURN);
        service.triggerEvent(order, OrderEvents.RELABEL);
        assertEquals(OrderStates.FBA_RELABELED, service.getCurrentState(order));

        // —— 重启 ——
        OrderStateMachineService restarted = afterRestart();
        assertEquals(OrderStates.FBA_RELABELED, restarted.getCurrentState(order),
                "重启后应仍是持久化的状态；若回到 CREATED 说明状态还留在进程内存里");

        // 重启后能从持久化状态继续推进（而不是只能读）
        assertTrue(restarted.triggerEvent(order, OrderEvents.DELIVER).accepted());
        assertEquals(OrderStates.DELIVERED, restarted.getCurrentState(order));
    }

    @Test
    void blankOrderIdFallsBackToDefault() {
        assertTrue(service.triggerEvent(null, OrderEvents.PAY).accepted());
        assertEquals(OrderStates.PAID, service.getCurrentState(OrderStateMachineService.DEFAULT_ORDER_ID));
    }

    @Test
    void resetReturnsToCreatedAndKeepsAuditTrail() {
        String order = "ORDER-RESET";
        service.triggerEvent(order, OrderEvents.PAY);
        assertEquals(OrderStates.PAID, service.getCurrentState(order));

        service.reset(order);

        assertEquals(OrderStates.CREATED, service.getCurrentState(order));
        // 重置也要留痕（审计完整），且轨迹在重置后依然可读
        List<OrderStateEventRecord> events = service.history(order);
        assertEquals(2, events.size());
        assertEquals("PAY", events.get(0).getEvent());
        assertEquals("RESET", events.get(1).getEvent());
        assertEquals(OrderStates.PAID, events.get(1).getFromState());
    }

    @Test
    @DisplayName("非法转换：状态不变，但必须在轨迹里留痕")
    void rejectedTransitionIsRecordedWithoutChangingState() {
        String order = "ORDER-REJECTED";

        OrderStateMachineService.StateTransition transition =
                service.triggerEvent(order, OrderEvents.FBA_RETURN);

        assertFalse(transition.accepted());
        assertEquals(OrderStates.CREATED, transition.fromState());
        assertEquals(OrderStates.CREATED, transition.toState());
        assertEquals(OrderStates.CREATED, service.getCurrentState(order));

        // 「客户说他点过按钮，为什么没生效」—— 靠这条记录回答
        List<OrderStateEventRecord> events = service.history(order);
        assertEquals(1, events.size());
        assertFalse(events.get(0).isAccepted());
        assertEquals("FBA_RETURN", events.get(0).getEvent());
    }

    @Test
    void historyIsOrderedByOccurrenceIncludingRejectedAttempts() {
        String order = "ORDER-HISTORY";
        service.triggerEvent(order, OrderEvents.PAY);
        service.triggerEvent(order, OrderEvents.PICK);
        service.triggerEvent(order, OrderEvents.SHIP);          // 合法
        service.triggerEvent(order, OrderEvents.PAY);           // 非法（SHIPPED 不能再 PAY）

        List<OrderStateEventRecord> events = service.history(order);

        assertEquals(List.of("PAY", "PICK", "SHIP", "PAY"),
                events.stream().map(OrderStateEventRecord::getEvent).toList());
        assertEquals(List.of(true, true, true, false),
                events.stream().map(OrderStateEventRecord::isAccepted).toList());
        assertEquals(OrderStates.SHIPPED, service.getCurrentState(order));
    }

    @Test
    void readOnlyStateQueryDoesNotCreateRows() {
        // 读操作不应产生写入：否则一个查询接口会把库写胖，也会让"从未推进"与"重置过"无法区分
        assertEquals(OrderStates.CREATED, service.getCurrentState("NEVER-TOUCHED"));

        assertEquals(0, stateRepository.count());
        assertEquals(0, eventRepository.count());
    }

    @Test
    @DisplayName("重置是幂等的：对从未推进过的订单调用 reset 不能炸（旧写法 deleteById 会抛 EmptyResultDataAccessException）")
    void resetOnUntouchedOrderIsIdempotent() {
        service.reset("ORDER-NEVER-ADVANCED");   // 不应抛异常

        assertEquals(OrderStates.CREATED, service.getCurrentState("ORDER-NEVER-ADVANCED"));
        // 仍然留了一条 RESET 审计（"谁在这个订单上做过复位动作"也是事实）
        assertEquals(1, service.history("ORDER-NEVER-ADVANCED").size());
    }

    @Test
    void trackedOrdersReflectsPersistedRows() {
        assertEquals(0, service.trackedOrders());
        service.triggerEvent("ORDER-1", OrderEvents.PAY);
        service.triggerEvent("ORDER-2", OrderEvents.PAY);
        assertEquals(2, service.trackedOrders());
        // 重启后计数仍来自数据库（不是内存计数）
        assertEquals(2, afterRestart().trackedOrders());
    }
}
