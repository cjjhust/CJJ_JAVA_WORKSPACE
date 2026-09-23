package com.aslp.report.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5 报表看板响应（report-service 对外的唯一"聚合根"）。
 *
 * <p><b>为什么要分块（orders / inventory）并各带 available 标志</b>：
 * 两个数据源互相独立，一个挂掉不该让整页白屏。分块 + {@code unavailable} 列表
 * 让前端能同时表达「库存部分正常」与「订单部分暂不可用」——
 * 这比整体返回 500 或整体返回「全 0」都更接近真相（全 0 会被误读成「今天真的没有订单」）。
 *
 * <p><b>为什么额外给 chart 结构</b>：前端图表库（ECharts）要的就是
 * {@code labels[] + values[]} 两个数组。让服务端把 map 摊平成数组，
 * 前端就不必再写一遍「取 key 当标签、取 value 当数值」的胶水代码 ——
 * 且顺序由服务端固定（{@code LinkedHashMap} 保序），同一份数据每次渲染颜色/顺序一致。
 */
public record DashboardReport(
        String service,
        String generatedAt,
        boolean degraded,
        List<String> unavailable,
        OrdersBlock orders,
        InventoryBlock inventory) {

    /** 订单区块：既保留原始计数，也给出可直接画图的摊平结构。 */
    public record OrdersBlock(
            boolean available,
            long total,
            long paid,
            long shipped,
            long completed,
            long withErrorTag,
            Map<String, Long> byStatus,
            Map<String, Long> byWarehouse,
            Map<String, Long> byErrorTag,
            ChartData statusChart,
            ChartData warehouseChart) {
    }

    /** 库存区块：低库存明细 + 阈值（阈值来自 inventory-service，报表不自造口径）。 */
    public record InventoryBlock(
            boolean available,
            int threshold,
            int lowStockCount,
            long secondsUntilMailAllowed,
            List<InventoryWarningSnapshot.LowStockItem> lowStock,
            ChartData lowStockChart) {
    }

    /** ECharts 友好的两数组结构。 */
    public record ChartData(List<String> labels, List<Long> values) {

        /** 由保序 map 摊平（迭代顺序即标签顺序）。 */
        public static ChartData of(Map<String, Long> source) {
            List<String> labels = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            for (Map.Entry<String, Long> entry : new LinkedHashMap<>(source).entrySet()) {
                labels.add(entry.getKey());
                values.add(entry.getValue() == null ? 0L : entry.getValue());
            }
            return new ChartData(List.copyOf(labels), List.copyOf(values));
        }
    }
}
