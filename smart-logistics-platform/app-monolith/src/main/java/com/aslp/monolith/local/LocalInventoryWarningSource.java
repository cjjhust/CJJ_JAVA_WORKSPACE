package com.aslp.monolith.local;

import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.task.InventoryWarningTask;
import com.aslp.report.client.InventoryWarningSource;
import com.aslp.report.client.ReportSourceUnavailableException;
import com.aslp.report.dto.InventoryWarningSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 库存预警的<b>进程内</b>数据源（单体拓扑专用，P2-7）。
 *
 * <p>直接注入 inventory 模块的 {@link InventoryWarningTask}（阈值/节流窗口口径的唯一真相源）
 * 与 {@link InventoryRepository}，与 {@code InventoryController#warningStatus()} 完全同构 ——
 * 微服务拓扑下这三个数走 HTTP（{@code GET /api/inventory/warnings/status}），字段一一对应。
 */
@Component
@Profile("monolith")
public class LocalInventoryWarningSource implements InventoryWarningSource {

    private final InventoryRepository repository;
    private final InventoryWarningTask warningTask;

    public LocalInventoryWarningSource(InventoryRepository repository, InventoryWarningTask warningTask) {
        this.repository = repository;
        this.warningTask = warningTask;
    }

    @Override
    public String getSource() {
        return "inventory-service";
    }

    @Override
    public InventoryWarningSnapshot fetch() {
        try {
            return new InventoryWarningSnapshot(
                    warningTask.threshold(),
                    warningTask.secondsUntilMailAllowed(),
                    repository.findByAvailableQtyLessThan(warningTask.threshold()).stream()
                            .map(item -> new InventoryWarningSnapshot.LowStockItem(
                                    item.getSku(),
                                    item.getWarehouseCode(),
                                    item.getAvailableQty(),
                                    item.getLockedQty()))
                            .toList());
        } catch (RuntimeException e) {
            throw new ReportSourceUnavailableException(getSource(), e);
        }
    }
}
