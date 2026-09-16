package com.aslp.inventory.controller;

import com.aslp.inventory.dto.InventoryView;
import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.InventoryLockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryRepository repository;
    private final InventoryLockService lockService;

    public InventoryController(InventoryRepository repository, InventoryLockService lockService) {
        this.repository = repository;
        this.lockService = lockService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "inventory-service up", "warehouse", "Bruchsal / Mönchengladbach"));
    }

    @PostMapping("/deduct")
    public ResponseEntity<Map<String, Object>> deduct(
            @RequestParam String sku,
            @RequestParam String warehouseCode,
            @RequestParam int qty) {
        boolean ok = lockService.deductInventory(sku, warehouseCode, qty);
        return ResponseEntity.ok(Map.of(
            "sku", sku,
            "warehouse", warehouseCode,
            "qty", qty,
            "success", ok
        ));
    }

    /**
     * P0-5 查询：按 SKU 查库存（可用 / 锁定双状态）。
     *
     * <p>GET /api/inventory/{sku}                      查全部仓库
     * <p>GET /api/inventory/{sku}?warehouseCode=xxx    只看指定仓库
     *
     * <p>响应统一为 {@link InventoryView}（汇总 + 各仓明细）；SKU 完全不存在时返回 404。
     */
    @GetMapping("/{sku}")
    public ResponseEntity<InventoryView> query(
            @PathVariable String sku,
            @RequestParam(required = false) String warehouseCode) {
        List<InventoryItem> items = (warehouseCode == null || warehouseCode.isBlank())
                ? repository.findBySku(sku)
                : repository.findBySkuAndWarehouseCode(sku, warehouseCode).map(List::of).orElseGet(List::of);
        if (items.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(InventoryView.from(sku, items));
    }

    /**
     * P0-5 释放锁定库存（支付失败 / 订单取消 / 超时未支付）。
     *
     * <p>对接 {@link InventoryLockService#releaseLockedInventory}；
     * 与 /deduct 保持一致的响应约定（业务失败也返回 200，由 success 字段表达）。
     */
    @PostMapping("/release")
    public ResponseEntity<Map<String, Object>> release(
            @RequestParam String sku,
            @RequestParam String warehouseCode,
            @RequestParam int qty) {
        boolean ok = lockService.releaseLockedInventory(sku, warehouseCode, qty);
        return ResponseEntity.ok(Map.of(
            "sku", sku,
            "warehouse", warehouseCode,
            "qty", qty,
            "success", ok
        ));
    }
}
