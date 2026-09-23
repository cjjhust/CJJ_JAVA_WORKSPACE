package com.aslp.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 库存侧的预警快照（inventory-service {@code GET /api/inventory/warnings/status} 的响应体）。
 *
 * <p>这个上游端点本身就是为「运维/看板」设计的诊断接口（P1-5 交付），
 * 报表服务直接复用，避免再写一套「低库存怎么算」的逻辑 —— 阈值口径只有一处真相源
 * （{@code aslp.inventory.warning.threshold}），否则看板与补货邮件迟早会对不上。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryWarningSnapshot(
        int threshold,
        long secondsUntilMailAllowed,
        List<LowStockItem> lowStock) {

    public InventoryWarningSnapshot {
        lowStock = lowStock == null ? List.of() : List.copyOf(lowStock);
    }

    /** 单条低库存明细。字段名必须与 inventory-service 的响应键一一对应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LowStockItem(
            String sku,
            String warehouse,
            int availableQty,
            int lockedQty) {
    }

    /**
     * 兜底快照：库存数据源不可用时使用。
     *
     * <p>阈值记 0 而不是「猜一个 10」：上游没答上来时必须看起来就是「不知道」，
     * 不能把报表里的默认值伪装成业务口径。
     */
    public static InventoryWarningSnapshot unavailable() {
        return new InventoryWarningSnapshot(0, 0, List.of());
    }
}
