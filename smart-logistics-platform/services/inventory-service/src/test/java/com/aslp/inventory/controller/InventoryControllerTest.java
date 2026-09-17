package com.aslp.inventory.controller;

import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.InventoryLockService;
import com.aslp.inventory.task.InventoryWarningTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InventoryController 切片测试。
 *
 * <p>历史 Bug：原测试使用 {@code @WebMvcTest} 但未提供 {@code InventoryRepository} /
 * {@code InventoryLockService} 依赖，上下文启动失败。现补 {@code @MockBean}。
 * 同时移除了不存在的 {@code test} profile 依赖（无 application-test.yml）。
 */
@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryRepository repository;

    @MockBean
    private InventoryLockService lockService;

    @MockBean
    private InventoryWarningTask warningTask;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/inventory/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inventory-service up"))
                .andExpect(jsonPath("$.warehouse").value("Bruchsal / Mönchengladbach"));
    }

    private static InventoryItem item(String warehouse, int available, int locked) {
        InventoryItem entity = new InventoryItem();
        entity.setSku("AMZ-1001");
        entity.setWarehouseCode(warehouse);
        entity.setAvailableQty(available);
        entity.setLockedQty(locked);
        entity.setUnitPrice(new BigDecimal("89.90"));
        return entity;
    }

    @Test
    @DisplayName("P0-5 查询：不带仓库参数时返回全部仓库汇总（按仓库编码排序）")
    void queryReturnsAllWarehouses() throws Exception {
        when(repository.findBySku("AMZ-1001")).thenReturn(List.of(
                item("Mönchengladbach", 95, 3),
                item("Bruchsal", 320, 12)));

        mockMvc.perform(get("/api/inventory/AMZ-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("AMZ-1001"))
                .andExpect(jsonPath("$.totalAvailable").value(415))
                .andExpect(jsonPath("$.totalLocked").value(15))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].warehouseCode").value("Bruchsal"))
                .andExpect(jsonPath("$.items[1].warehouseCode").value("Mönchengladbach"));
    }

    @Test
    @DisplayName("P0-5 查询：按仓库过滤时只返回该仓明细")
    void queryFiltersByWarehouse() throws Exception {
        when(repository.findBySkuAndWarehouseCode("AMZ-1001", "Bruchsal"))
                .thenReturn(Optional.of(item("Bruchsal", 318, 14)));

        mockMvc.perform(get("/api/inventory/AMZ-1001").param("warehouseCode", "Bruchsal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAvailable").value(318))
                .andExpect(jsonPath("$.totalLocked").value(14))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].availableQty").value(318))
                .andExpect(jsonPath("$.items[0].lockedQty").value(14));
    }

    @Test
    @DisplayName("P0-5 查询：SKU 完全不存在时返回 404")
    void queryReturns404ForUnknownSku() throws Exception {
        when(repository.findBySku("NOPE")).thenReturn(List.of());

        mockMvc.perform(get("/api/inventory/NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("P0-5 释放：服务成功后透出 success=true")
    void releaseReturnsSuccess() throws Exception {
        when(lockService.releaseLockedInventory("AMZ-1001", "Bruchsal", 2)).thenReturn(true);

        mockMvc.perform(post("/api/inventory/release")
                        .param("sku", "AMZ-1001")
                        .param("warehouseCode", "Bruchsal")
                        .param("qty", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("AMZ-1001"))
                .andExpect(jsonPath("$.warehouse").value("Bruchsal"))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("P0-5 释放：服务拒绝（超锁定量）时透出 success=false")
    void releaseReturnsFailure() throws Exception {
        when(lockService.releaseLockedInventory("AMZ-1001", "Bruchsal", 50)).thenReturn(false);

        mockMvc.perform(post("/api/inventory/release")
                        .param("sku", "AMZ-1001")
                        .param("warehouseCode", "Bruchsal")
                        .param("qty", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("P1-5 手动触发预警：透出 lowStockCount / mailSent / forced")
    void triggerWarningReturnsScanResult() throws Exception {
        when(warningTask.scan(true)).thenReturn(
                new InventoryWarningTask.WarningScanResult(2, 10, true, false, "已发送补货建议邮件"));

        mockMvc.perform(post("/api/inventory/warnings/trigger").param("force", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStockCount").value(2))
                .andExpect(jsonPath("$.threshold").value(10))
                .andExpect(jsonPath("$.mailSent").value(true))
                .andExpect(jsonPath("$.mailSkipped").value(false))
                .andExpect(jsonPath("$.forced").value(true));
    }

    @Test
    @DisplayName("P1-5 手动触发预警：默认 force=true（CLI 里不用额外带参数）")
    void triggerWarningDefaultsToForce() throws Exception {
        when(warningTask.scan(true)).thenReturn(
                new InventoryWarningTask.WarningScanResult(0, 10, false, false, "库存充足"));

        mockMvc.perform(post("/api/inventory/warnings/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailSent").value(false));
    }

    @Test
    @DisplayName("P1-5 预警状态：透出阈值、节流剩余秒数与低库存明细")
    void warningStatusExposesLowStockAndThrottle() throws Exception {
        when(warningTask.threshold()).thenReturn(10);
        when(warningTask.secondsUntilMailAllowed()).thenReturn(1800L);
        when(repository.findByAvailableQtyLessThan(10))
                .thenReturn(List.of(item("Bruchsal", 4, 0)));

        mockMvc.perform(get("/api/inventory/warnings/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(10))
                .andExpect(jsonPath("$.secondsUntilMailAllowed").value(1800))
                .andExpect(jsonPath("$.lowStock.length()").value(1))
                .andExpect(jsonPath("$.lowStock[0].warehouse").value("Bruchsal"));
    }
}
