package com.aslp.inventory.task;

import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.ReplenishmentMailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M2 库存预警定时任务测试（不依赖 Spring 调度与真实 SMTP）。
 *
 * <p>覆盖两条关键行为：
 * <ol>
 *   <li>阈值边界：以 {@code findByAvailableQtyLessThan(10)} 查询，且只为命中的 SKU 发邮件；</li>
 *   <li>异常隔离：单个 SKU 的邮件发送失败不得中断后续 SKU 的处理
 *       （历史缺陷：SMTP 不可用会让整轮预警停摆）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class InventoryWarningTaskTest {

    /** 与实现中的安全库存阈值一致（低于 10 触发预警）。 */
    private static final int SAFETY_STOCK_THRESHOLD = 10;

    @Mock
    private InventoryRepository repository;

    @Mock
    private ReplenishmentMailService mailService;

    private InventoryWarningTask task;

    @BeforeEach
    void setUp() {
        task = new InventoryWarningTask(repository, mailService);
    }

    private static InventoryItem lowStock(String sku, String warehouse, int availableQty) {
        InventoryItem item = new InventoryItem();
        item.setSku(sku);
        item.setWarehouseCode(warehouse);
        item.setAvailableQty(availableQty);
        item.setLockedQty(0);
        return item;
    }

    @Test
    @DisplayName("库存充足：不发送任何补货邮件，且按阈值 10 查询")
    void sendsNoMailWhenStockIsHealthy() {
        when(repository.findByAvailableQtyLessThan(SAFETY_STOCK_THRESHOLD)).thenReturn(List.of());

        task.checkLowStock();

        verify(repository).findByAvailableQtyLessThan(SAFETY_STOCK_THRESHOLD);
        verifyNoInteractions(mailService);
    }

    @Test
    @DisplayName("每个低库存 SKU 各触发一次补货建议，并透传仓库与当前库存")
    void sendsOneSuggestionPerLowStockSku() {
        when(repository.findByAvailableQtyLessThan(SAFETY_STOCK_THRESHOLD)).thenReturn(List.of(
                lowStock("AMZ-9999", "Mönchengladbach", 5),
                lowStock("AMZ-1002", "Mönchengladbach", 9)));

        task.checkLowStock();

        verify(mailService).sendReplenishmentSuggestion("AMZ-9999", "Mönchengladbach", 5);
        verify(mailService).sendReplenishmentSuggestion("AMZ-1002", "Mönchengladbach", 9);
    }

    @Test
    @DisplayName("异常隔离：某个 SKU 的邮件失败后，后续 SKU 仍会被处理且整体不抛异常")
    void mailFailureDoesNotAbortRemainingSkus() {
        when(repository.findByAvailableQtyLessThan(SAFETY_STOCK_THRESHOLD)).thenReturn(List.of(
                lowStock("AMZ-9999", "Mönchengladbach", 5),
                lowStock("EBAY-2001", "Bruchsal", 3)));
        doThrow(new MailSendException("SMTP 未就绪"))
                .when(mailService).sendReplenishmentSuggestion("AMZ-9999", "Mönchengladbach", 5);

        assertDoesNotThrow(() -> task.checkLowStock());

        verify(mailService).sendReplenishmentSuggestion("EBAY-2001", "Bruchsal", 3);
    }
}
