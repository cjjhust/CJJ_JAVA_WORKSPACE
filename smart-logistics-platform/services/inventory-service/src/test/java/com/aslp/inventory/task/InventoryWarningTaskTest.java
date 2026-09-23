package com.aslp.inventory.task;

import com.aslp.inventory.config.WarningProperties;
import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.InMemoryMailThrottle;
import com.aslp.inventory.service.MailThrottle;
import com.aslp.inventory.service.ReplenishmentMailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M2 库存预警任务测试（不依赖 Spring 调度与真实 SMTP）。
 *
 * <p>P1-5 新增的重点是<b>邮件节流</b>：扫描周期（60s）与发信周期（默认 30 分钟）
 * 是两个独立概念。旧实现「扫一次发 N 封」，在 SMTP 不可用时无法暴露；
 * 邮件真的通了以后会变成「邮件轰炸」。这里把节流行为钉死：
 * 首次发、间隔内跳过、{@code force=true} 绕过。
 */
@ExtendWith(MockitoExtension.class)
class InventoryWarningTaskTest {

    private static final int THRESHOLD = 10;

    @Mock
    private InventoryRepository repository;

    @Mock
    private ReplenishmentMailService mailService;

    private WarningProperties properties;
    private InventoryWarningTask task;

    /** 真实的进程内节流实现（不是 mock）：节流语义本身就是要被测的对象。 */
    private MailThrottle throttle;

    @BeforeEach
    void setUp() {
        properties = new WarningProperties();
        properties.setThreshold(THRESHOLD);
        properties.setMailInterval(Duration.ofMinutes(30));
        throttle = new InMemoryMailThrottle(properties.getMailInterval());
        task = new InventoryWarningTask(repository, mailService, properties, throttle);
    }

    private static InventoryItem lowStock(String sku, String warehouse, int availableQty) {
        InventoryItem item = new InventoryItem();
        item.setSku(sku);
        item.setWarehouseCode(warehouse);
        item.setAvailableQty(availableQty);
        item.setLockedQty(0);
        return item;
    }

    private List<InventoryItem> twoLowStockSkus() {
        return List.of(
                lowStock("AMZ-9999", "Mönchengladbach", 5),
                lowStock("EBAY-2001", "Bruchsal", 3));
    }

    @Test
    @DisplayName("库存充足：不发信，且按配置阈值查询")
    void sendsNoMailWhenStockIsHealthy() {
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(List.of());

        InventoryWarningTask.WarningScanResult result = task.scan(false);

        verify(repository).findByAvailableQtyLessThan(THRESHOLD);
        verifyNoInteractions(mailService);
        assertEquals(0, result.lowStockCount());
        assertFalse(result.mailSent());
        assertFalse(result.mailSkipped());
    }

    @Test
    @DisplayName("首次扫描：一封汇总邮件带上全部低库存 SKU（不是每个 SKU 一封）")
    void sendsSingleDigestForAllLowStockSkus() {
        List<InventoryItem> lowStock = twoLowStockSkus();
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(lowStock);
        when(mailService.sendReplenishmentDigest(lowStock)).thenReturn(true);

        InventoryWarningTask.WarningScanResult result = task.scan(false);

        verify(mailService, times(1)).sendReplenishmentDigest(lowStock);
        assertTrue(result.mailSent());
        assertFalse(result.mailSkipped());
        assertEquals(2, result.lowStockCount());
    }

    @Test
    @DisplayName("节流：间隔内的第二轮扫描跳过发信（但仍在巡检）")
    void skipsMailWithinInterval() {
        List<InventoryItem> lowStock = twoLowStockSkus();
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(lowStock);
        when(mailService.sendReplenishmentDigest(lowStock)).thenReturn(true);

        task.scan(false);
        InventoryWarningTask.WarningScanResult second = task.scan(false);

        verify(mailService, times(1)).sendReplenishmentDigest(anyList());
        assertFalse(second.mailSent());
        assertTrue(second.mailSkipped(), "必须显式标记「本轮因节流跳过」，而不是静默什么都不做");
        assertEquals(2, second.lowStockCount(), "巡检本身不受节流影响");
        assertTrue(task.secondsUntilMailAllowed() > 0);
    }

    @Test
    @DisplayName("手动触发（force=true）：绕过节流立即发信（显式动作应当立即生效）")
    void forceBypassesThrottle() {
        List<InventoryItem> lowStock = twoLowStockSkus();
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(lowStock);
        when(mailService.sendReplenishmentDigest(lowStock)).thenReturn(true);

        task.scan(false);
        InventoryWarningTask.WarningScanResult forced = task.scan(true);

        verify(mailService, times(2)).sendReplenishmentDigest(anyList());
        assertTrue(forced.mailSent());
        assertFalse(forced.mailSkipped());
    }

    @Test
    @DisplayName("发信失败不计入节流窗口：下一轮应立即重试（否则会白等 30 分钟）")
    void failedMailDoesNotStartThrottleWindow() {
        List<InventoryItem> lowStock = twoLowStockSkus();
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(lowStock);
        when(mailService.sendReplenishmentDigest(lowStock)).thenReturn(false);

        task.scan(false);
        InventoryWarningTask.WarningScanResult second = task.scan(false);

        verify(mailService, times(2)).sendReplenishmentDigest(anyList());
        assertFalse(second.mailSkipped(), "失败的那次不应把节流窗口打开");
        assertEquals(0, task.secondsUntilMailAllowed());
    }

    @Test
    @DisplayName("异常隔离：邮件服务抛异常时巡检不中断、不向上抛")
    void mailFailureDoesNotAbortScan() {
        List<InventoryItem> lowStock = twoLowStockSkus();
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(lowStock);
        doThrow(new MailSendException("SMTP 未就绪")).when(mailService).sendReplenishmentDigest(lowStock);

        assertDoesNotThrow(() -> task.scan(false));

        verify(mailService).sendReplenishmentDigest(lowStock);
        verify(repository).findByAvailableQtyLessThan(THRESHOLD);
    }

    @Test
    @DisplayName("阈值来自配置：改配置即生效，不需要改代码")
    void thresholdComesFromConfiguration() {
        properties.setThreshold(25);
        when(repository.findByAvailableQtyLessThan(25)).thenReturn(List.of());

        task.scan(false);

        verify(repository).findByAvailableQtyLessThan(25);
        assertEquals(25, task.threshold());
        verify(mailService, never()).sendReplenishmentDigest(anyList());
    }

    @Test
    @DisplayName("P1-10：节流窗口在共享存储里 —— 「重启后的新实例」也必须被拦住")
    void throttleWindowSurvivesANewInstance() {
        List<InventoryItem> lowStock = twoLowStockSkus();
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(lowStock);
        when(mailService.sendReplenishmentDigest(lowStock)).thenReturn(true);

        task.scan(false);

        // 模拟重启/多副本：新的任务实例 + 同一个节流实现（生产里是同一个 Redis 键）
        InventoryWarningTask restarted =
                new InventoryWarningTask(repository, mailService, properties, throttle);
        InventoryWarningTask.WarningScanResult afterRestart = restarted.scan(false);

        verify(mailService, times(1)).sendReplenishmentDigest(anyList());
        assertTrue(afterRestart.mailSkipped(),
                "新实例仍必须处于节流窗口内；若在此处就发信，说明窗口还在进程内（多副本会成倍发信）");
    }

    @Test
    @DisplayName("P1-10：发信失败要归还资格（不是把窗口留着让下一轮白等）")
    void failedSendReleasesTheAcquiredSlot() {
        List<InventoryItem> lowStock = twoLowStockSkus();
        when(repository.findByAvailableQtyLessThan(THRESHOLD)).thenReturn(lowStock);
        when(mailService.sendReplenishmentDigest(lowStock)).thenReturn(false);

        task.scan(false);

        // 归还后窗口应当立刻打开（上一版语义：失败不计入节流窗口）
        assertEquals(0, task.secondsUntilMailAllowed(),
                "失败必须释放资格，否则一次 SMTP 抖动会让告警白停 30 分钟");
    }
}
