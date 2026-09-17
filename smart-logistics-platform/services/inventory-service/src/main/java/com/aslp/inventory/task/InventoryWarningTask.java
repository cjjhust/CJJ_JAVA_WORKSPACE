package com.aslp.inventory.task;

import com.aslp.inventory.config.WarningProperties;
import com.aslp.inventory.entity.InventoryItem;
import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.ReplenishmentMailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M2 库存预警任务（P1-5 起接入真实邮件链路）。
 *
 * <p><b>本轮的两个修正</b>：
 * <ol>
 *   <li><b>邮件节流</b>：任务每 {@code check-interval}（默认 60s）扫一次，但补货邮件
 *       至少间隔 {@code mail-interval}（默认 30 分钟）才发一封。旧实现是「扫一次发
 *       N 封」，且因为 SMTP 不可用、异常被吞，这个「邮件轰炸」缺陷一直没暴露 ——
 *       邮件真的通了以后，收件人一天会收到上千封重复邮件。运维手动触发时用
 *       {@code force=true} 绕过节流（显式动作应当立即生效）；</li>
 *   <li><b>日志进 SLF4J</b>：原实现用 {@code System.out.println}，既没有级别也无法
 *       被 Loki 的 {@code level} 标签过滤；补货告警属于 WARN（要人处理），
 *       「跳过发送」属于 INFO（正常节流）。</li>
 * </ol>
 */
@Component
public class InventoryWarningTask {

    private static final Logger log = LoggerFactory.getLogger(InventoryWarningTask.class);

    private final InventoryRepository repository;
    private final ReplenishmentMailService mailService;
    private final WarningProperties properties;

    /** 上次成功发送补货邮件的时间（进程内即可：节流是单实例防护，多实例时可换 Redis）。 */
    private final AtomicReference<Instant> lastMailAt = new AtomicReference<>();

    public InventoryWarningTask(InventoryRepository repository, ReplenishmentMailService mailService,
                                WarningProperties properties) {
        this.repository = repository;
        this.mailService = mailService;
        this.properties = properties;
    }

    /** 一轮扫描结果（供运维手动触发的接口回显）。 */
    public record WarningScanResult(int lowStockCount, int threshold, boolean mailSent,
                                    boolean mailSkipped, String message) {
    }

    /**
     * 定时巡检（受邮件节流约束）。
     *
     * <p><b>为什么是毫秒数的属性名</b>：{@code @Scheduled} 的 String 形式只接受
     * 「纯数字毫秒」或 ISO-8601（{@code PT60S}），<b>不认 Boot 的 {@code 60s} 简写</b>
     * （那是 {@code @ConfigurationProperties} 绑定器才有的能力）。
     * 写成 {@code 60s} 会在启动时直接失败：
     * {@code Invalid fixedDelayString value "60s"; NumberFormatException}
     * （见 readme §9 #44）。
     */
    @Scheduled(fixedDelayString = "${aslp.inventory.warning.check-interval-ms:60000}")
    public void checkLowStock() {
        scan(false);
    }

    /** 当前生效的安全库存阈值（只读透出，供诊断接口与运维核对配置）。 */
    public int threshold() {
        return properties.getThreshold();
    }

    /**
     * 执行一轮低库存巡检。
     *
     * @param forceMail true = 忽略节流强制发信（运维手动触发用）
     */
    public WarningScanResult scan(boolean forceMail) {
        List<InventoryItem> lowStock = repository.findByAvailableQtyLessThan(properties.getThreshold());
        if (lowStock.isEmpty()) {
            log.debug("[库存预警] 无可用库存低于 {} 的 SKU", properties.getThreshold());
            return new WarningScanResult(0, properties.getThreshold(), false, false, "库存充足，无需补货");
        }

        log.warn("[库存预警] 可用库存低于 {} 的 SKU 共 {} 个：{}",
                properties.getThreshold(), lowStock.size(),
                lowStock.stream().map(InventoryItem::getSku).toList());

        if (!forceMail && !mailDue()) {
            log.info("[库存预警] 距上次补货邮件不足 {}，本轮跳过发送（避免邮件轰炸）",
                    properties.getMailInterval());
            return new WarningScanResult(lowStock.size(), properties.getThreshold(), false, true,
                    "低库存 " + lowStock.size() + " 个；因节流跳过发信（间隔 "
                            + properties.getMailInterval() + "）");
        }

        boolean sent = false;
        try {
            sent = mailService.sendReplenishmentDigest(lowStock);
        } catch (RuntimeException e) {
            // 兜底：邮件失败绝不能中断巡检（历史缺陷：SMTP 不可用会让整轮预警停摆）
            log.warn("[库存预警] 补货邮件异常已隔离，巡检继续：{}", e.toString());
        }
        if (sent) {
            lastMailAt.set(Instant.now());
        }
        return new WarningScanResult(lowStock.size(), properties.getThreshold(), sent, false,
                sent ? "已发送补货建议邮件" : "低库存 " + lowStock.size() + " 个；邮件未发出（SMTP 不可用？）");
    }

    /** 是否到达可发信时间点。 */
    private boolean mailDue() {
        Instant last = lastMailAt.get();
        if (last == null) {
            return true;
        }
        return !Instant.now().isBefore(last.plus(properties.getMailInterval()));
    }

    /** 距离下次可发信的剩余秒数（诊断用，0 表示现在就可以发）。 */
    public long secondsUntilMailAllowed() {
        Instant last = lastMailAt.get();
        if (last == null) {
            return 0;
        }
        long remain = last.plus(properties.getMailInterval()).getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remain);
    }
}
