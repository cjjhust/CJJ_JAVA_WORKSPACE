package com.gdpr.audit.gdpr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GDPR PII 数据脱敏工具 — 在审计日志写入前对敏感个人信息进行掩码处理。
 *
 * 覆盖三类 PII（正则独立实现，便于扩展新类型）：
 * - 邮箱：john.doe@example.com      → j*****e@e****e.com
 * - 电话（含德国格式）：+49 170 1234567 → +49 *** ***4567
 * - IBAN：DE89370400440532013000    → DE89****3000
 *
 * 注意：本类只处理审计日志中的副本，不修改原始请求/响应对象。
 */
public final class GdprDataMasker {

    /** 邮箱：local@domain.tld（支持多级子域名与点号用户名） */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\w.]+@[\\w.]+\\.[a-zA-Z]{2,}");

    /**
     * 电话号码：以 + 或 0 开头的 9 位以上数字串，允许空格/横线分隔。
     * 下限设为 9 位有效数字，避免误判短号码（如订单号 12345）。
     */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("\\+?[\\d][\\d\\s-]{7,}\\d");

    /** IBAN：两位国家码 + 两位校验位 + 多组字母数字（末组允许 2-4 位，兼容 DE...3000 尾部短组） */
    private static final Pattern IBAN_PATTERN =
            Pattern.compile("[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4})* ?[A-Z0-9]{2,4}");

    /** 单条审计文本的最大处理长度，防止 MB 级文本上的正则性能问题 */
    private static final int MAX_INPUT_LENGTH = 100_000;

    private GdprDataMasker() {
    }

    /** 统一入口：依次对邮箱、电话、IBAN 脱敏 */
    public static String maskPii(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            input = input.substring(0, MAX_INPUT_LENGTH);
        }
        String result = maskEmails(input);
        // IBAN 先于电话：IBAN 是更具体的模式，若先跑电话正则会把 IBAN 数字串误判为电话
        result = maskIbans(result);
        result = maskPhoneNumbers(result);
        return result;
    }

    /** 邮箱脱敏：保留首字符与 TLD，其余掩码 — j*****e@e****e.com */
    public static String maskEmails(String input) {
        Matcher matcher = EMAIL_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(maskEmail(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 电话脱敏：保留国家/区号前缀，其余掩码 — +49 *** ***4567 */
    public static String maskPhoneNumbers(String input) {
        Matcher matcher = PHONE_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(maskPhone(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** IBAN 脱敏：保留国家码+校验位与前 2 位、末 4 位 — DE89****3000 */
    public static String maskIbans(String input) {
        Matcher matcher = IBAN_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(maskIban(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);

        String maskedLocal = keepEnds(local);
        int lastDot = domain.lastIndexOf('.');
        if (lastDot <= 0) {
            return maskedLocal + "@" + maskAll(domain);
        }
        String tld = domain.substring(lastDot); // ".com"
        String domainBody = domain.substring(0, lastDot);
        return maskedLocal + "@" + keepEnds(domainBody) + tld;
    }

    private static String maskPhone(String phone) {
        String digits = phone.replaceAll("[\\s-]", "");
        if (digits.length() < 9) {
            return phone; // 太短，大概率不是电话，避免误伤
        }
        String prefix = phone.startsWith("+") ? phone.substring(0, 3) : digits.substring(0, 3);
        String last4 = digits.substring(digits.length() - 4);
        return prefix + " *** ***" + last4;
    }

    private static String maskIban(String iban) {
        if (iban.length() < 8) {
            return "***";
        }
        return iban.substring(0, 4) + "****" + iban.substring(iban.length() - 4);
    }

    /** 首字符 + "*****" + 尾字符 */
    private static String keepEnds(String s) {
        if (s.length() <= 2) {
            return maskAll(s);
        }
        return s.charAt(0) + "*****" + s.charAt(s.length() - 1);
    }

    private static String maskAll(String s) {
        return "*".repeat(Math.max(1, s.length()));
    }
}
