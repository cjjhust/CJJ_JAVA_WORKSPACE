package com.aslp.report.service;

import com.aslp.report.client.InventoryWarningClient;
import com.aslp.report.client.OrderStatsClient;
import com.aslp.report.client.ReportSourceUnavailableException;
import com.aslp.report.config.ReportProperties;
import com.aslp.report.dto.DashboardReport;
import com.aslp.report.dto.DashboardReport.ChartData;
import com.aslp.report.dto.DashboardReport.InventoryBlock;
import com.aslp.report.dto.DashboardReport.OrdersBlock;
import com.aslp.report.dto.InventoryWarningSnapshot;
import com.aslp.report.dto.OrderStatsSnapshot;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * M5 报表聚合服务：把各业务服务的既有能力拼成一张看板。
 *
 * <h3>它做什么、不做什么</h3>
 * <ul>
 *   <li><b>做</b>：并发/顺序调用下游只读接口、把结果摊平成图表结构、在部分失败时降级。</li>
 *   <li><b>不做</b>：不做业务计算，也不直连别人的数据库。低库存口径来自 inventory-service，
 *       订单计数口径来自 order-service —— 报表只是「搬运 + 整形」。
 *       这样「低库存到底怎么算」永远只有一处实现，看板不会与补货邮件打架。</li>
 * </ul>
 *
 * <h3>失败语义：部分降级，而不是整体失败</h3>
 * 任一数据源不可用时：<b>HTTP 仍返回 200</b>，对应区块 {@code available=false}，
 * 并把它记进顶层 {@code unavailable} 列表（前端据此显示"订单数据暂不可用"的提示条）。
 * 这与项目里既有的降级约定一致（order-service 的熔断降级返回 200 + {@code degraded=true}）：
 * 一个依赖抖动就整页 500，等于把「部分可用」也一起丢掉 —— 看板尤其不能这样，
 * 它本来就是用来"看见问题"的。
 *
 * <h3>可观测性（P1-1 / P1-3）</h3>
 * 一次聚合产出 span + 指标 {@code aslp.report.dashboard}。
 * {@code degraded} 是低基数标签（true/false）→ 进指标，可用来对"看板降级率"告警；
 * 具体哪个源挂了属于高基数，只放进 span，同时写日志 WARN（带异常栈，便于定位）。
 */
@Service
public class ReportAggregationService {

    /** 观测名：同时作为 span 名与指标名前缀（Prometheus: aslp_report_dashboard_seconds_*）。 */
    public static final String OBSERVATION_DASHBOARD = "aslp.report.dashboard";

    private static final Logger log = LoggerFactory.getLogger(ReportAggregationService.class);

    private final OrderStatsClient orderStatsClient;
    private final InventoryWarningClient inventoryWarningClient;
    private final ObservationRegistry observationRegistry;
    private final ReportProperties props;

    public ReportAggregationService(OrderStatsClient orderStatsClient,
                                    InventoryWarningClient inventoryWarningClient,
                                    ObservationRegistry observationRegistry,
                                    ReportProperties props) {
        this.orderStatsClient = orderStatsClient;
        this.inventoryWarningClient = inventoryWarningClient;
        this.observationRegistry = observationRegistry;
        this.props = props;
    }

    /** 组装完整看板（订单 + 库存）。 */
    public DashboardReport dashboard() {
        Observation observation = Observation.createNotStarted(OBSERVATION_DASHBOARD, observationRegistry).start();
        // openScope 把 traceId 放进 MDC：日志里能直接搜到同一条链路（P1-3）
        try (Observation.Scope ignored = observation.openScope()) {
            List<String> unavailable = new ArrayList<>();
            OrdersBlock orders = fetchOrders(unavailable);
            InventoryBlock inventory = fetchInventory(unavailable);

            boolean degraded = !unavailable.isEmpty();
            observation.lowCardinalityKeyValue("degraded", String.valueOf(degraded));
            // 高基数信息（具体哪个源、多少条低库存）只进 span，不进指标
            observation.highCardinalityKeyValue("unavailable", String.join(",", unavailable));
            observation.highCardinalityKeyValue("lowStockCount", String.valueOf(inventory.lowStockCount()));

            return new DashboardReport(
                    "report-service",
                    Instant.now().toString(),
                    degraded,
                    List.copyOf(unavailable),
                    orders,
                    inventory);
        }
    }

    /** 只看订单区块（单独暴露给前端按需刷新）。 */
    public OrdersBlock orders() {
        return fetchOrders(new ArrayList<>());
    }

    /** 只看库存区块。 */
    public InventoryBlock inventory() {
        return fetchInventory(new ArrayList<>());
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    private OrdersBlock fetchOrders(List<String> unavailable) {
        try {
            OrderStatsSnapshot stats = orderStatsClient.fetch();
            return new OrdersBlock(
                    true,
                    stats.total(),
                    stats.paid(),
                    stats.shipped(),
                    stats.completed(),
                    stats.withErrorTag(),
                    stats.byStatus(),
                    stats.byWarehouse(),
                    stats.byErrorTag(),
                    ChartData.of(stats.byStatus()),
                    ChartData.of(stats.byWarehouse()));
        } catch (ReportSourceUnavailableException e) {
            // 通知渠道失败不该拖垮整页：记 WARN（带栈）+ 降级，与 P1-5 邮件同一条处理原则
            log.warn("订单统计不可用，看板降级：{}", e.getMessage(), e);
            unavailable.add(e.getSource());
            OrderStatsSnapshot empty = OrderStatsSnapshot.unavailable();
            return new OrdersBlock(false, empty.total(), empty.paid(), empty.shipped(),
                    empty.completed(), empty.withErrorTag(),
                    empty.byStatus(), empty.byWarehouse(), empty.byErrorTag(),
                    ChartData.of(empty.byStatus()), ChartData.of(empty.byWarehouse()));
        }
    }

    private InventoryBlock fetchInventory(List<String> unavailable) {
        try {
            InventoryWarningSnapshot snapshot = inventoryWarningClient.fetch();
            // 截断保护：低库存 SKU 可能有几百条，看板只需要「最紧急的前 N 条」
            List<InventoryWarningSnapshot.LowStockItem> items =
                    snapshot.lowStock().size() > props.getLowStockLimit()
                            ? snapshot.lowStock().subList(0, props.getLowStockLimit())
                            : snapshot.lowStock();
            return new InventoryBlock(
                    true,
                    snapshot.threshold(),
                    snapshot.lowStock().size(),
                    snapshot.secondsUntilMailAllowed(),
                    items,
                    lowStockChart(items));
        } catch (ReportSourceUnavailableException e) {
            log.warn("库存预警不可用，看板降级：{}", e.getMessage(), e);
            unavailable.add(e.getSource());
            InventoryWarningSnapshot empty = InventoryWarningSnapshot.unavailable();
            return new InventoryBlock(false, empty.threshold(), 0,
                    empty.secondsUntilMailAllowed(), empty.lowStock(), ChartData.of(java.util.Map.of()));
        }
    }

    /** 低库存柱状图：标签为「SKU@仓库」，值为可用库存（越短越紧急）。 */
    private static ChartData lowStockChart(List<InventoryWarningSnapshot.LowStockItem> items) {
        List<String> labels = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (InventoryWarningSnapshot.LowStockItem item : items) {
            labels.add(item.sku() + "@" + item.warehouse());
            values.add((long) item.availableQty());
        }
        return new ChartData(List.copyOf(labels), List.copyOf(values));
    }
}
