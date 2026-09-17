package com.aslp.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * P1-5：库存预警与补货邮件参数（前缀 {@code aslp.inventory.warning}）。
 *
 * <p>把这些从代码常量搬进配置，是为了让「阈值 / 收件人 / 发信频率」这三件
 * <b>运营会改的事</b>不需要改代码、重新构建镜像 —— 它们是业务口径，不是代码常量。
 */
@ConfigurationProperties(prefix = "aslp.inventory.warning")
public class WarningProperties {

    /** 安全库存阈值：可用库存低于该值触发预警。 */
    private int threshold = 10;

    /** 补货目标倍数：建议补货至 {@code threshold × multiplier}（覆盖一个补货周期）。 */
    private int targetMultiplier = 2;

    /** 补货邮件收件人（脱敏演示地址，保留域 .internal 不可投递，见 readme §12）。 */
    private String recipient = "warehouse-manager@aslp.internal";

    /** 邮件主题前缀，便于在收件箱里规则化归档。 */
    private String subjectPrefix = "[库存补货建议]";

    /**
     * 两封补货邮件之间的最小间隔。
     *
     * <p><b>为什么必须有这个节流</b>：预警任务默认每 60 秒扫一次，若每次都发信，
     * 收件人一天会收到上千封重复邮件（而且这个缺陷在 SMTP 不可用时被“发送失败”掩盖，
     * 一旦邮件真的通了就会立刻暴露）。运维手动触发时可用 {@code force=true} 绕过。
     */
    private Duration mailInterval = Duration.ofMinutes(30);

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public int getTargetMultiplier() {
        return targetMultiplier;
    }

    public void setTargetMultiplier(int targetMultiplier) {
        this.targetMultiplier = targetMultiplier;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public Duration getMailInterval() {
        return mailInterval;
    }

    public void setMailInterval(Duration mailInterval) {
        this.mailInterval = mailInterval;
    }

    /** 补货目标水位（= 阈值 × 倍数）。 */
    public int targetStock() {
        return Math.max(threshold, threshold * Math.max(1, targetMultiplier));
    }

    /** 收件人是否配置（空值时不发信，避免把邮件发到空地址）。 */
    public boolean hasRecipient() {
        return StringUtils.hasText(recipient);
    }
}
