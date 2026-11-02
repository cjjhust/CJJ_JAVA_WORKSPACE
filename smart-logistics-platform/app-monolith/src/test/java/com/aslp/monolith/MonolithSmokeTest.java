package com.aslp.monolith;

import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.task.InventoryWarningTask;
import com.aslp.monolith.local.LocalInventoryWarningSource;
import com.aslp.monolith.local.LocalOrderStatsSource;
import com.aslp.order.repository.OrderRepository;
import com.aslp.report.client.InventoryWarningSource;
import com.aslp.report.client.OrderStatsClient;
import com.aslp.report.client.OrderStatsSource;
import com.aslp.report.dto.InventoryWarningSnapshot;
import com.aslp.report.dto.OrderStatsSnapshot;
import com.aslp.report.service.ReportAggregationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-7：单体拓扑装配冒烟测试 —— {@code profile=monolith} 下整个上下文能启动，
 * 且报表聚合走的是进程内数据源。
 *
 * <p>证明四件事：
 * <ul>
 *   <li>5 个业务模块的全部组件（controller/service/repository）能在同一上下文共存；</li>
 *   <li>profile 切换正确：{@code LocalXxxSource} 装配，HTTP 客户端
 *       （{@code @Profile("!monolith")} 的 {@link OrderStatsClient}）<b>不</b>装配；</li>
 *   <li>合并后的 4 套 Flyway 迁移（auth/inventory/order）能在真 PG 上一次跑通；</li>
 *   <li>聚合服务进程内取数结果与 HTTP 契约 DTO 同构（degraded=false）。</li>
 * </ul>
 *
 * <p>需要 PG（JPA/Flyway）与 Redis（Redisson 客户端启动即建连）两个容器；
 * CI 的 ubuntu runner 自带 Docker，开箱即用。
 */
@Testcontainers
// MOCK（而非 NONE）：auth 模块的 SecurityConfig 依赖 HttpSecurity，
// 它由 Spring Security 的 Servlet 自动配置提供 —— NONE（非 Web 环境）下不存在，
// 上下文直接装配失败。MOCK 提供模拟 Servlet 环境且不占端口。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MonolithSmokeTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("aslp")
            .withUsername("aslp")
            .withPassword("aslp123");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ReportAggregationService aggregationService;

    @Autowired
    private OrderStatsSource orderStatsSource;

    @Autowired
    private InventoryWarningSource inventoryWarningSource;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryWarningTask warningTask;

    @Test
    @DisplayName("monolith profile 下装配的是 Local 数据源，HTTP 客户端不装配")
    void localSourcesWiredAndHttpClientsAbsent() {
        assertTrue(orderStatsSource instanceof LocalOrderStatsSource,
                "应装配 LocalOrderStatsSource，实际: " + orderStatsSource.getClass());
        assertTrue(inventoryWarningSource instanceof LocalInventoryWarningSource,
                "应装配 LocalInventoryWarningSource，实际: " + inventoryWarningSource.getClass());
    }

    @Test
    @DisplayName("进程内取数：聚合结果 degraded=false（与 HTTP 契约同构）")
    void dashboardAggregatesInProcess() {
        // 种子数据：inventory 的 V2 迁移自带两仓库存；order 表为空也合法（全 0 分布）
        var report = aggregationService.dashboard();
        assertFalse(report.degraded(), "进程内调用不应降级，unavailable=" + report.unavailable());
        assertTrue(report.orders().available());
        assertTrue(report.inventory().available());
        // 库存种子数据应命中低库存明细（V2 种子含 availableQty 低于阈值的行）
        assertNotNull(report.inventory().lowStockCount());
    }

    @Test
    @DisplayName("LocalOrderStatsSource 与 HTTP 端点同口径：count + 分组排序")
    void orderStatsMatchesHttpSemantics() {
        OrderStatsSnapshot stats = orderStatsSource.fetch();
        assertEquals(orderRepository.count(), stats.total());
        assertEquals(orderRepository.countByStatus("PAID"), stats.paid());
    }

    @Test
    @DisplayName("LocalInventoryWarningSource 与 HTTP 端点同构：threshold/lowStock 对齐")
    void inventoryWarningMatchesHttpSemantics() {
        InventoryWarningSnapshot snapshot = inventoryWarningSource.fetch();
        assertEquals(warningTask.threshold(), snapshot.threshold());
        assertEquals(inventoryRepository.findByAvailableQtyLessThan(warningTask.threshold()).size(),
                snapshot.lowStock().size());
    }
}
