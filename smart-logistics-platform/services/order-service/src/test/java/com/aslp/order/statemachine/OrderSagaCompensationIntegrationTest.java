package com.aslp.order.statemachine;

import com.aslp.order.entity.OrderStateEventRecord;
import com.aslp.order.repository.OrderStateEventRepository;
import com.aslp.order.repository.OrderStateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4：Saga 补偿骨架的「数据库行为」集成测试（真 PostgreSQL via Testcontainers）。
 *
 * <p><b>Saga 在本项目的形态</b>：微服务拆库后，"下单锁库存" 无法再用一个本地事务包住，
 * 演进为编排式 Saga —— 订单创建（本地事务1）→ 库存锁定（本地事务2）→
 * 失败时订单补偿取消（本地事务3，逆向撤销）。当前代码库就是这套骨架：
 * <ul>
 *   <li>{@code COMPENSATE} 事件 = 补偿触发（仅 CREATED/PAID 合法——PICKED 后实物已动）；</li>
 *   <li>{@code CANCELLED} 状态 = 补偿终点；</li>
 *   <li>{@code order_state_event} 表 = Saga 日志（每次迁移含被拒绝的都留痕，可审计）。</li>
 * </ul>
 *
 * <p>本测试用真 PG 钉死三条 Saga 语义（H2 测不出：迁移 SQL 是 PG 方言）：
 * 补偿只允许从 CREATED/PAID 出发；补偿后事件表有完整正/逆轨迹；重启（新实例读库）不丢补偿结果。
 */
@Testcontainers
// 缺陷 #57 同款：禁用 @DataJpaTest 的 H2 替换，强制跑在 Testcontainers 的真 PG 上
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/order",
        "spring.flyway.table=flyway_schema_history_order",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(OrderStateMachineService.class)
class OrderSagaCompensationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("aslp")
            .withUsername("aslp")
            .withPassword("aslp123");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private OrderStateRepository stateRepository;

    @Autowired
    private OrderStateEventRepository eventRepository;

    @Autowired
    private OrderStateMachineService service;

    /** 模拟「服务重启」：全新实例读同一个库（Saga 日志必须不丢）。 */
    private OrderStateMachineService afterRestart() {
        return new OrderStateMachineService(stateRepository, eventRepository);
    }

    @Test
    @DisplayName("Saga 正向：CREATED→PAID 后锁库存失败，COMPENSATE 到 CANCELLED")
    void compensateFromPaidCancelsOrder() {
        String orderId = "SAGA-001";
        assertTrue(service.triggerEvent(orderId, OrderEvents.PAY).accepted());

        // 模拟「锁库存失败」触发补偿（真实链路中由编排器/事件监听调用）
        var compensation = service.triggerEvent(orderId, OrderEvents.COMPENSATE);

        assertTrue(compensation.accepted());
        assertEquals(OrderStates.PAID, compensation.fromState());
        assertEquals(OrderStates.CANCELLED, compensation.toState());
        assertEquals(OrderStates.CANCELLED, service.getCurrentState(orderId));
    }

    @Test
    @DisplayName("Saga 边界：PICKED（实物已动）不允许 COMPENSATE，拒绝也留痕")
    void compensateRejectedAfterPick() {
        String orderId = "SAGA-002";
        service.triggerEvent(orderId, OrderEvents.PAY);
        service.triggerEvent(orderId, OrderEvents.PICK);

        var rejected = service.triggerEvent(orderId, OrderEvents.COMPENSATE);

        assertFalse(rejected.accepted(), "PICKED 后实物已动，必须走 FBA_RETURN 逆向流程");
        assertEquals(OrderStates.PICKED, service.getCurrentState(orderId));
        // 被拒绝的事件同样落库（Saga 日志回答"为什么补偿没生效"）
        List<OrderStateEventRecord> history = service.history(orderId);
        assertTrue(history.stream().anyMatch(e -> "COMPENSATE".equals(e.getEvent()) && !e.isAccepted()));
    }

    @Test
    @DisplayName("Saga 日志持久化：补偿后重启（新实例读库）轨迹完整可审计")
    void sagaLogSurvivesRestart() {
        String orderId = "SAGA-003";
        service.triggerEvent(orderId, OrderEvents.PAY);
        service.triggerEvent(orderId, OrderEvents.COMPENSATE);

        // 重启：进程内状态归零，全部从 DB 恢复
        OrderStateMachineService restarted = afterRestart();

        assertEquals(OrderStates.CANCELLED, restarted.getCurrentState(orderId));
        List<OrderStateEventRecord> history = restarted.history(orderId);
        assertEquals(2, history.size());
        assertEquals("PAY", history.get(0).getEvent());
        assertEquals("COMPENSATE", history.get(1).getEvent());
    }
}
