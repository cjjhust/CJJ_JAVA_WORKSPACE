package com.aslp.inventory.dto;

import com.aslp.inventory.entity.InventoryItem;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * P0-5 库存查询响应体（SKU 维度汇总 + 各仓明细）。
 *
 * <p>设计意图：无论是否按仓库过滤，都返回同一种结构，客户端无需分支处理；
 * 汇总字段由服务端算好，避免前端自行累加。
 *
 * <p>为什么用 record 而不是 {@code Map.of(...)}：{@code unitPrice} 在库中允许为空，
 * 而 {@code Map.of} 拒绝 null 值（同类问题参见 readme 缺陷 #23：BFF 因 Map.of + null 返回 500）。
 */
public record InventoryView(
        String sku,
        int totalAvailable,
        int totalLocked,
        List<WarehouseStock> items) {

    /** 单仓库存明细。 */
    public record WarehouseStock(
            String warehouseCode,
            int availableQty,
            int lockedQty,
            BigDecimal unitPrice) {
    }

    /**
     * 由实体列表装配响应：按仓库编码排序保证输出稳定（便于断言与人工比对）。
     */
    public static InventoryView from(String sku, List<InventoryItem> items) {
        List<WarehouseStock> stock = items.stream()
                .sorted(Comparator.comparing(InventoryItem::getWarehouseCode))
                .map(item -> new WarehouseStock(
                        item.getWarehouseCode(),
                        nz(item.getAvailableQty()),
                        nz(item.getLockedQty()),
                        item.getUnitPrice()))
                .toList();
        int totalAvailable = stock.stream().mapToInt(WarehouseStock::availableQty).sum();
        int totalLocked = stock.stream().mapToInt(WarehouseStock::lockedQty).sum();
        return new InventoryView(sku, totalAvailable, totalLocked, stock);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
