package com.aslp.inventory.controller;

import com.aslp.inventory.dto.InventoryView;
import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.InventoryLockService;
import com.aslp.inventory.task.InventoryWarningTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryRepository repository;
    private final InventoryLockService lockService;
    private final InventoryWarningTask warningTask;

    public InventoryController(InventoryRepository repository, InventoryLockService lockService,
                               InventoryWarningTask warningTask) {
        this.repository = repository;
        this.lockService = lockService;
        this.warningTask = warningTask;
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

    /**
     * P1-5 预警状态（只读诊断）：谁低于阈值、阈值多少、还要等多久才能再发信。
     *
     * <p>对应运维场景「补货邮件没来」：先看这里而不是先翻日志 —— 是没命中阈值、
     * 还是被节流拦了、还是 SMTP 挂了（看 /actuator/health 的 mail 组件）。
     */
    @GetMapping("/warnings/status")
    public ResponseEntity<Map<String, Object>> warningStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threshold", warningTask.threshold());
        body.put("secondsUntilMailAllowed", warningTask.secondsUntilMailAllowed());
        body.put("lowStock", repository.findByAvailableQtyLessThan(warningTask.threshold()).stream()
                .map(item -> Map.of(
                        "sku", item.getSku(),
                        "warehouse", item.getWarehouseCode(),
                        "availableQty", item.getAvailableQty(),
                        "lockedQty", item.getLockedQty()))
                .toList());
        return ResponseEntity.ok(body);
    }

    /**
     * P1-5 手动触发一轮低库存巡检（运维补发 / 验证邮件链路）。
     *
     * <p>{@code force=true} 绕过节流：显式的人工动作应当立即生效，
     * 不应该因为「30 分钟内刚发过」而被默默拒掉。
     * 网关在 docker profile 下已把 {@code POST /api/inventory/**} 限为 ADMIN。
     */
    @PostMapping("/warnings/trigger")
    public ResponseEntity<Map<String, Object>> triggerWarning(
            @RequestParam(defaultValue = "true") boolean force) {
        InventoryWarningTask.WarningScanResult result = warningTask.scan(force);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lowStockCount", result.lowStockCount());
        body.put("threshold", result.threshold());
        body.put("mailSent", result.mailSent());
        body.put("mailSkipped", result.mailSkipped());
        body.put("forced", force);
        body.put("message", result.message());
        return ResponseEntity.ok(body);
    }
}
