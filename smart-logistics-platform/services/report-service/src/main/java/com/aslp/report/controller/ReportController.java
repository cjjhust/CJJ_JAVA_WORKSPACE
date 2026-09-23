package com.aslp.report.controller;

import com.aslp.report.dto.DashboardReport;
import com.aslp.report.service.ReportAggregationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M1/M5 报表与数据可视化接口。
 *
 * <p><b>P1-7 变更（本轮）</b>：原实现是「硬编码假数据的桩」——
 * {@code dashboard()} 直接返回 {@code List.of(120, 200, 150, ...)} 常量数组，
 * 既没有任何数据源，也无法反映系统真实状态（属于 M1 里程碑里唯一未交付的能力）。
 * 现改为**真实聚合**：订单数据来自 order-service {@code /api/orders/stats}，
 * 低库存数据来自 inventory-service {@code /api/inventory/warnings/status}。
 *
 * <p>接口分工：
 * <ul>
 *   <li>{@code GET /api/reports/dashboard} —— 前端看板一次拿全（订单 + 库存）；</li>
 *   <li>{@code GET /api/reports/orders} —— 只要订单区块（前端局部刷新，避免无谓调用库存）；</li>
 *   <li>{@code GET /api/reports/inventory} —— 只要库存区块；</li>
 *   <li>{@code GET /api/reports/health} —— 健康检查（网关路由与冒烟脚本使用）。</li>
 * </ul>
 *
 * <p>静态看板页面在 {@code classpath:/static/dashboard.html}，
 * 由本服务直接托管（同源，免 CORS 配置），ECharts 从 CDN 加载。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportAggregationService aggregationService;

    public ReportController(ReportAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "report-service up",
                "feature", "ECharts 数据可视化看板（真实聚合：order-service + inventory-service）",
                "milestone", "M1 / M5"
        ));
    }

    /** 看板全量数据（订单分布 + 低库存预警）。任一上游不可用时返回 200 + degraded=true。 */
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardReport> dashboard() {
        return ResponseEntity.ok(aggregationService.dashboard());
    }

    /** 订单区块（单独暴露，便于前端按需刷新）。 */
    @GetMapping("/orders")
    public ResponseEntity<DashboardReport.OrdersBlock> orders() {
        return ResponseEntity.ok(aggregationService.orders());
    }

    /** 库存区块（单独暴露）。 */
    @GetMapping("/inventory")
    public ResponseEntity<DashboardReport.InventoryBlock> inventory() {
        return ResponseEntity.ok(aggregationService.inventory());
    }
}
