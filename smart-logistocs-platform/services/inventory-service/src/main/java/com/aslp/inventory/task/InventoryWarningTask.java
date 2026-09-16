package com.aslp.inventory.task;

import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.ReplenishmentMailService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InventoryWarningTask {

    /** 安全库存阈值（低于该值触发预警 + 补货邮件） */
    private static final int SAFETY_STOCK_THRESHOLD = 10;

    private final InventoryRepository repository;
    private final ReplenishmentMailService mailService;

    public InventoryWarningTask(InventoryRepository repository, ReplenishmentMailService mailService) {
        this.repository = repository;
        this.mailService = mailService;
    }

    @Scheduled(fixedRate = 60000) // 每 60 秒检查一次
    public void checkLowStock() {
        List<InventoryItem> lowStock = repository.findByAvailableQtyLessThan(SAFETY_STOCK_THRESHOLD);
        if (!lowStock.isEmpty()) {
            System.out.println("[库存预警] 安全库存不足（<" + SAFETY_STOCK_THRESHOLD
                    + "），涉及 SKU 数量：" + lowStock.size());
            // M2 补货邮件：为每个低库存 SKU 触发补货建议（邮件失败不影响库存检测）
            for (InventoryItem item : lowStock) {
                try {
                    mailService.sendReplenishmentSuggestion(
                            item.getSku(), item.getWarehouseCode(), item.getAvailableQty());
                } catch (Exception e) {
                    System.out.println("[库存预警] 补货邮件发送失败（SMTP 未就绪）："
                            + item.getSku() + " - " + e.getMessage());
                }
            }
        }
    }
}
