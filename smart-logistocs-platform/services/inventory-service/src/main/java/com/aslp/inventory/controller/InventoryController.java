package com.aslp.inventory.controller;

import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.InventoryLockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
