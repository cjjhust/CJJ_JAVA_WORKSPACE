package com.aslp.inventory.service;

import com.aslp.inventory.config.WarningProperties;
import com.aslp.inventory.entity.InventoryItem;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P1-5 补货邮件服务：Thymeleaf 渲染 HTML 正文 + multipart/alternative 纯文本兜底。
 *
 * <p><b>与旧实现的三个差别</b>：
 * <ol>
 *   <li>正文由模板渲染（{@code templates/email/replenishment.html}），而不是把中文
 *       拼在 Java 字符串里 —— 改版式不再需要重新编译、也不会因拼接漏空格；</li>
 *   <li>一次扫描只发<b>一封汇总邮件</b>而不是每个 SKU 一封（旧实现是 N 封）：收件人
 *       看到的是一张待办清单，而不是被邮件淹没；</li>
 *   <li>纯文本作为 alternative 一并发送：部分邮件客户端/网关只认 text/plain，
 *       没有兑底就会出现「收到空邮件」。</li>
 * </ol>
 *
 * <p><b>失败语义（尽力而为）</b>：任何 SMTP 异常都被吞掉并记 WARN，返回
 * {@code false}。<b>不抛异常是刻意的</b> —— 邮件是预警的「通知渠道」，不是预警本身；
 * 邮件发不出去不应该让库存巡检链路（定时任务 / 运维手动触发）跟着报错。
 *
 * <p>业务时区固定为 {@code Europe/Berlin}：仓库在德国，邮件里的时间必须是收件人
 * 所在时区的时间（容器是 UTC，直接用系统默认时区会差 1~2 小时）。
 */
@Service
public class ReplenishmentMailService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentMailService.class);

    /** 邮件模板名（相对 {@code classpath:/templates/}，后缀由解析器补 .html）。 */
    public static final String TEMPLATE_NAME = "email/replenishment";

    /** 仓库所在时区（邮件中的时间按收件人所在时区展示）。 */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Berlin");

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.CHINA);

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final WarningProperties properties;

    public ReplenishmentMailService(JavaMailSender mailSender, SpringTemplateEngine templateEngine,
                                    WarningProperties properties) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.properties = properties;
    }

    /**
     * 单行补货建议（模板渲染后的展示模型）。
     *
     * @param critical 是否告急（可用库存已低于阈值一半）
     */
    public record ReplenishmentItem(String sku, String warehouseCode, int availableQty,
                                    int lockedQty, int suggestedQty, boolean critical) {
    }

    /** 一轮扫描的汇总邮件：低库存 SKU 全部列在一封邮件里。 */
    public boolean sendReplenishmentDigest(List<InventoryItem> lowStock) {
        List<ReplenishmentItem> items = toItems(lowStock);
        if (items.isEmpty()) {
            log.debug("[补货邮件] 无低库存 SKU，跳过发送");
            return false;
        }
        if (!properties.hasRecipient()) {
            log.warn("[补货邮件] 未配置收件人（aslp.inventory.warning.recipient），跳过发送");
            return false;
        }

        String subject = buildSubject(items);
        // 渲染放在 try 之外：**模板写错是代码缺陷，不是 SMTP 故障**。
        // 若一并吞掉，模板里一个变量名拼错会永久静默地“发送失败”，无人察觉。
        String html = renderHtml(items);
        String plainText = buildPlainText(items);
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true：正文同时带 text/plain 与 text/html 两份
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(properties.getRecipient());
            helper.setSubject(subject);
            helper.setText(plainText, html);
            mailSender.send(message);
            log.info("[补货邮件] 已发送 → {}（SKU {} 个，主题：{}）",
                    properties.getRecipient(), items.size(), subject);
            return true;
        } catch (Exception e) {
            log.warn("[补货邮件] 发送失败（SMTP 是否就绪？见 compose 的 aslp_mailhog）：{}", e.toString());
            return false;
        }
    }

    /** 渲染 HTML 正文（拆成独立方法：单元测试可以在不碰 SMTP 的情况下断言模板输出）。 */
    public String renderHtml(List<ReplenishmentItem> items) {
        Context context = new Context(Locale.CHINA);
        context.setVariable("items", items);
        context.setVariable("lowStockCount", items.size());
        context.setVariable("criticalCount", items.stream().filter(ReplenishmentItem::critical).count());
        context.setVariable("threshold", properties.getThreshold());
        context.setVariable("targetStock", properties.targetStock());
        context.setVariable("targetMultiplier", Math.max(1, properties.getTargetMultiplier()));
        context.setVariable("recipient", properties.getRecipient());
        context.setVariable("generatedAt", ZonedDateTime.now(BUSINESS_ZONE).format(TIMESTAMP));
        return templateEngine.process(TEMPLATE_NAME, context);
    }

    /** 主题：单个 SKU 时带单号与仓库（便于直接分派），多个时给数量与告急数。 */
    public String buildSubject(List<ReplenishmentItem> items) {
        String prefix = properties.getSubjectPrefix();
        if (items.size() == 1) {
            ReplenishmentItem only = items.get(0);
            return prefix + " " + only.sku() + "@" + only.warehouseCode()
                    + " 剩余 " + only.availableQty() + "（阈值 " + properties.getThreshold() + "）";
        }
        long critical = items.stream().filter(ReplenishmentItem::critical).count();
        return prefix + " " + items.size() + " 个 SKU 低于安全库存"
                + "（阈值 " + properties.getThreshold() + "，其中告急 " + critical + " 个）";
    }

    /** 纯文本正文（老客户端 / 纯文本网关的兜底）。 */
    public String buildPlainText(List<ReplenishmentItem> items) {
        StringBuilder text = new StringBuilder();
        text.append("ASLP 库存补货建议\n");
        text.append("生成时间：").append(ZonedDateTime.now(BUSINESS_ZONE).format(TIMESTAMP)).append('\n');
        text.append("触发原因：").append(items.size())
                .append(" 个 SKU 的可用库存低于安全阈值 ").append(properties.getThreshold()).append('\n');
        text.append("建议补货水位：").append(properties.targetStock()).append('\n');
        text.append("--------------------------------------------------\n");
        for (ReplenishmentItem item : items) {
            text.append(item.critical() ? "[告急] " : "[偏低] ")
                    .append(item.sku()).append('@').append(item.warehouseCode())
                    .append("  可用 ").append(item.availableQty())
                    .append(" / 锁定 ").append(item.lockedQty())
                    .append(" / 建议补货 ").append(item.suggestedQty())
                    .append('\n');
        }
        text.append("--------------------------------------------------\n");
        text.append("建议动作：核实在途采购单 → 优先处理告急行 → 下单后回填采购单号。\n");
        text.append("本邮件由 ASLP 库存预警任务自动发送（演示环境，收件域名不可投递）。\n");
        return text.toString();
    }

    /** 库存实体 → 邮件展示模型（补货量与告急判定都在这里，模板只管展示）。 */
    public List<ReplenishmentItem> toItems(List<InventoryItem> lowStock) {
        List<ReplenishmentItem> items = new ArrayList<>();
        if (lowStock == null) {
            return items;
        }
        int threshold = properties.getThreshold();
        int target = properties.targetStock();
        for (InventoryItem item : lowStock) {
            int available = item.getAvailableQty();
            items.add(new ReplenishmentItem(
                    item.getSku(),
                    item.getWarehouseCode(),
                    available,
                    item.getLockedQty(),
                    Math.max(0, target - available),
                    available < Math.max(1, threshold) / 2));
        }
        return items;
    }
}
