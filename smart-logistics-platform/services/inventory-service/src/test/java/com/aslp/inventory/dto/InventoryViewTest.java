package com.aslp.inventory.dto;

import com.aslp.inventory.entity.InventoryItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5 库存查询响应装配测试：汇总求和 + 仓库排序 + 空值防御。
 *
 * <p>排序是接口契约的一部分：输出顺序稳定才能让前端与断言可靠。
 * 空值防御对应库表可空列（unit_price 允许为空）。
 */
class InventoryViewTest {

    private static InventoryItem item(String warehouse, Integer available, Integer locked, BigDecimal price) {
        InventoryItem entity = new InventoryItem();
        entity.setSku("AMZ-1001");
        entity.setWarehouseCode(warehouse);
        entity.setAvailableQty(available);
        entity.setLockedQty(locked);
        entity.setUnitPrice(price);
        return entity;
    }

    @Test
    @DisplayName("汇总两仓可用/锁定量，并按仓库编码升序排列明细")
    void aggregatesTotalsAndSortsWarehouses() {
        InventoryView view = InventoryView.from("AMZ-1001", List.of(
                item("Mönchengladbach", 95, 3, new BigDecimal("89.90")),
                item("Bruchsal", 320, 12, new BigDecimal("89.90"))));

        assertEquals("AMZ-1001", view.sku());
        assertEquals(415, view.totalAvailable());
        assertEquals(15, view.totalLocked());
        assertEquals(2, view.items().size());
        assertEquals("Bruchsal", view.items().get(0).warehouseCode(), "仓库编码升序：B 在 M 之前");
        assertEquals("Mönchengladbach", view.items().get(1).warehouseCode());
    }

    @Test
    @DisplayName("空列表：汇总为 0 且明细为空（不抛异常）")
    void emptyListYieldsZeroTotals() {
        InventoryView view = InventoryView.from("NOPE", List.of());

        assertEquals(0, view.totalAvailable());
        assertEquals(0, view.totalLocked());
        assertTrue(view.items().isEmpty());
    }

    @Test
    @DisplayName("可空列防御：数量与单价为 null 时按 0 计，不影响汇总")
    void toleratesNullColumns() {
        InventoryView view = InventoryView.from("AMZ-1001", List.of(item("Bruchsal", null, null, null)));

        assertEquals(0, view.totalAvailable());
        assertEquals(0, view.totalLocked());
        assertEquals(0, view.items().get(0).availableQty());
        assertNull(view.items().get(0).unitPrice(), "单价可空，不应抛异常");
    }
}
