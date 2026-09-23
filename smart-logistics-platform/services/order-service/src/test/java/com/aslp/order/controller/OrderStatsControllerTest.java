package com.aslp.order.controller;

import com.aslp.order.repository.OrderRepository;
import com.aslp.order.service.OrderPullService;
import com.aslp.order.strategy.PlatformFailureSwitch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/orders/stats} 的分组统计测试（M5 报表看板的数据源）。
 *
 * <p>为什么需要一个专门的测试：这个端点的输出会被 report-service 直接消费，
 * 一旦键名/顺序变化，看板会静默变成空图（而不是报错）。用测试把「契约」钉住，
 * 比事后对着一个空图表排查便宜得多。
 */
class OrderStatsControllerTest {

    private final OrderRepository repository = Mockito.mock(OrderRepository.class);
    private final OrderPullService pullService = Mockito.mock(OrderPullService.class);
    private final PlatformFailureSwitch failureSwitch = Mockito.mock(PlatformFailureSwitch.class);

    private final OrderPullController controller =
            new OrderPullController(pullService, repository, failureSwitch);

    @SuppressWarnings("unchecked")
    private Map<String, Object> statsBody() {
        ResponseEntity<Map<String, Object>> response = controller.stats();
        return response.getBody();
    }

    @Test
    void statsDistributionsAreSortedSoChartsAndAssertionsStayStable() {
        when(repository.count()).thenReturn(3L);
        when(repository.countByStatus("PAID")).thenReturn(2L);
        when(repository.countByStatus("SHIPPED")).thenReturn(1L);
        when(repository.countByErrorTagNotNull()).thenReturn(1L);
        // 故意按「数据库可能返回的顺序」给：group by 不保证行序（Postgres 可能走 HashAggregate）。
        // 若原样透传，看板图表颜色/顺序会随机跳变，端到端断言也会时通时断。
        when(repository.countGroupByStatus()).thenReturn(List.<Object[]>of(
                new Object[]{"PAID", 2L}, new Object[]{"CREATED", 1L}));
        when(repository.countGroupByWarehouseCode()).thenReturn(List.<Object[]>of(
                new Object[]{"Mönchengladbach", 1L}, new Object[]{"Bruchsal", 2L}));
        when(repository.countGroupByErrorTag()).thenReturn(List.<Object[]>of(
                new Object[]{"ADDRESS_INVALID", 1L}));

        Map<String, Object> body = statsBody();

        // 旧字段保持不动（冒烟脚本断言 "total":3 / "withErrorTag":0）
        assertEquals(3L, body.get("total"));
        assertEquals(1L, body.get("withErrorTag"));

        Map<String, Long> byStatus = (Map<String, Long>) body.get("byStatus");
        Map<String, Long> byWarehouse = (Map<String, Long>) body.get("byWarehouse");
        Map<String, Long> byErrorTag = (Map<String, Long>) body.get("byErrorTag");

        // 输出必须按键排序（键序即图表顺序），与数据库返回顺序无关
        assertEquals(List.of("CREATED", "PAID"), List.copyOf(byStatus.keySet()));
        assertEquals(List.of("Bruchsal", "Mönchengladbach"), List.copyOf(byWarehouse.keySet()));
        assertEquals(List.of("ADDRESS_INVALID"), List.copyOf(byErrorTag.keySet()));
        assertEquals(2L, byStatus.get("PAID"));
    }

    @Test
    void nullGroupKeyBecomesUnknownRatherThanJsonNull() {
        when(repository.countGroupByWarehouseCode()).thenReturn(List.<Object[]>of(
                new Object[]{null, 2L}));
        when(repository.countGroupByStatus()).thenReturn(List.of());
        when(repository.countGroupByErrorTag()).thenReturn(List.of());

        Map<String, Long> byWarehouse = (Map<String, Long>) statsBody().get("byWarehouse");

        // 未分配履约仓的订单在库中 warehouseCode 可能为空；JSON 里的 null 键会让部分图表库直接报错
        assertTrue(byWarehouse.containsKey("UNKNOWN"));
        assertEquals(2L, byWarehouse.get("UNKNOWN"));
    }

    @Test
    void emptyDistributionsAreEmptyMapsNotMissingKeys() {
        when(repository.countGroupByStatus()).thenReturn(List.of());
        when(repository.countGroupByWarehouseCode()).thenReturn(List.of());
        when(repository.countGroupByErrorTag()).thenReturn(List.of());

        Map<String, Object> body = statsBody();

        // 键必须存在（值为空 map）：报表侧据此判断"确实没有数据"，
        // 而不是把缺键当成解析失败
        assertEquals(Map.of(), body.get("byStatus"));
        assertEquals(Map.of(), body.get("byWarehouse"));
        assertEquals(Map.of(), body.get("byErrorTag"));
    }
}
