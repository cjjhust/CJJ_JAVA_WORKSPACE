package com.aslp.report.service;

import com.aslp.report.client.InventoryWarningClient;
import com.aslp.report.client.OrderStatsClient;
import com.aslp.report.client.ReportSourceUnavailableException;
import com.aslp.report.config.ReportProperties;
import com.aslp.report.dto.DashboardReport;
import com.aslp.report.dto.InventoryWarningSnapshot;
import com.aslp.report.dto.OrderStatsSnapshot;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 报表聚合逻辑单测（M5）。
 *
 * <p>重点不在"能取到数据"，而在<b>部分失败时的行为</b>：
 * 一个上游挂掉时，另一个区块必须仍然可用，且响应要如实标注缺了谁 ——
 * 这条语义比任何字段映射都更容易在重构中被破坏，所以用测试钉死。
 */
class ReportAggregationServiceTest {

    private OrderStatsClient orderStatsClient;
    private InventoryWarningClient inventoryWarningClient;
    private ReportProperties props;
    private ReportAggregationService service;

    @BeforeEach
    void setUp() {
        orderStatsClient = Mockito.mock(OrderStatsClient.class);
        inventoryWarningClient = Mockito.mock(InventoryWarningClient.class);
        props = new ReportProperties();
        props.setLowStockLimit(2);
        service = new ReportAggregationService(orderStatsClient, inventoryWarningClient,
                ObservationRegistry.create(), props);
    }

    private static OrderStatsSnapshot orders(long total) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("CREATED", 1L);
        byStatus.put("PAID", total - 1);
        Map<String, Long> byWarehouse = new LinkedHashMap<>();
        byWarehouse.put("Bruchsal", total);
        Map<String, Long> byErrorTag = new LinkedHashMap<>();
        byErrorTag.put("ADDRESS_INVALID", 1L);
        return new OrderStatsSnapshot(total, total - 1, 1, 0, 1, byStatus, byWarehouse, byErrorTag);
    }

    private static InventoryWarningSnapshot warning(List<InventoryWarningSnapshot.LowStockItem> items) {
        return new InventoryWarningSnapshot(10, 900, items);
    }

    private static InventoryWarningSnapshot.LowStockItem item(String sku, int qty) {
        return new InventoryWarningSnapshot.LowStockItem(sku, "Mönchengladbach", qty, 0);
    }

    @Test
    void healthySourcesProduceNonDegradedDashboardWithChartData() {
        when(orderStatsClient.fetch()).thenReturn(orders(3));
        when(inventoryWarningClient.fetch()).thenReturn(warning(List.of(item("AMZ-9999", 5))));

        DashboardReport report = service.dashboard();

        assertFalse(report.degraded());
        assertTrue(report.unavailable().isEmpty());
        assertEquals("report-service", report.service());
        assertTrue(report.orders().available());
        assertTrue(report.inventory().available());
        // 图表数据必须是「标签 + 数值」两数组，且与 map 顺序一致
        assertEquals(List.of("CREATED", "PAID"), report.orders().statusChart().labels());
        assertEquals(List.of(1L, 2L), report.orders().statusChart().values());
        assertEquals(List.of("Bruchsal"), report.orders().warehouseChart().labels());
        assertEquals(10, report.inventory().threshold());
    }

    @Test
    void orderSourceFailureDegradesOnlyThatBlock() {
        when(orderStatsClient.fetch())
                .thenThrow(new ReportSourceUnavailableException(OrderStatsClient.SOURCE, new RuntimeException("boom")));
        when(inventoryWarningClient.fetch()).thenReturn(warning(List.of(item("AMZ-9999", 5))));

        DashboardReport report = service.dashboard();

        assertTrue(report.degraded());
        assertEquals(List.of("order-service"), report.unavailable());
        // 关键断言：订单挂了，库存区块仍必须是"可用且真实"的，而不是跟着一起变空
        assertFalse(report.orders().available());
        assertTrue(report.inventory().available());
        assertEquals(1, report.inventory().lowStockCount());
        assertEquals(0, report.orders().total());
        assertTrue(report.orders().statusChart().labels().isEmpty());
    }

    @Test
    void inventorySourceFailureDegradesOnlyThatBlock() {
        when(orderStatsClient.fetch()).thenReturn(orders(3));
        when(inventoryWarningClient.fetch())
                .thenThrow(new ReportSourceUnavailableException(InventoryWarningClient.SOURCE, new RuntimeException("boom")));

        DashboardReport report = service.dashboard();

        assertTrue(report.degraded());
        assertEquals(List.of("inventory-service"), report.unavailable());
        assertTrue(report.orders().available());
        assertFalse(report.inventory().available());
        // 阈值无人应答时必须是 0（"不知道"），不能拿默认值伪装成业务口径
        assertEquals(0, report.inventory().threshold());
    }

    @Test
    void bothSourcesDownStillReturnsDashboardInsteadOfThrowing() {
        when(orderStatsClient.fetch()).thenThrow(
                new ReportSourceUnavailableException(OrderStatsClient.SOURCE, new RuntimeException("boom")));
        when(inventoryWarningClient.fetch()).thenThrow(
                new ReportSourceUnavailableException(InventoryWarningClient.SOURCE, new RuntimeException("boom")));

        DashboardReport report = service.dashboard();

        assertTrue(report.degraded());
        assertEquals(List.of("order-service", "inventory-service"), report.unavailable());
    }

    @Test
    void lowStockDetailIsTruncatedToConfiguredLimitButCountStaysTruthful() {
        when(orderStatsClient.fetch()).thenReturn(orders(3));
        when(inventoryWarningClient.fetch()).thenReturn(warning(List.of(
                item("SKU-A", 1), item("SKU-B", 2), item("SKU-C", 3))));

        DashboardReport report = service.dashboard();

        // 明细被截到 2 条（保护响应体），但 lowStockCount 仍是真实总数 3
        // —— 否则看板会说"只有 2 个低库存 SKU"，那是错的。
        assertEquals(2, report.inventory().lowStock().size());
        assertEquals(3, report.inventory().lowStockCount());
        assertEquals(List.of("SKU-A@Mönchengladbach", "SKU-B@Mönchengladbach"),
                report.inventory().lowStockChart().labels());
    }

    @Test
    void singleBlockEndpointsDoNotReportDegradation() {
        // /api/reports/orders 只取订单区块：上游失败时应表现为 available=false，
        // 而不是让整个端点变成"降级响应"（没有第二个源可供对比）
        when(orderStatsClient.fetch())
                .thenThrow(new ReportSourceUnavailableException(OrderStatsClient.SOURCE, new RuntimeException("boom")));

        DashboardReport.OrdersBlock block = service.orders();

        assertFalse(block.available());
        assertEquals(0, block.total());
    }
}
