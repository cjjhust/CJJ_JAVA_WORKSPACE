package com.aslp.route.tracking;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 承运商时间戳解析（M3 追踪的隐藏坑点）。
 *
 * <p><b>为什么不能直接 {@code Instant.parse}</b>：DHL 与 DPD 的时间戳有<b>三种</b>形态：
 * <ol>
 *   <li>带 {@code Z}/偏移：{@code 2026-09-20T10:00:00Z} → 直接用；</li>
 *   <li>不带时区的本地时间：{@code 2026-09-20T10:00:00} → **它是德国本地时间（Europe/Berlin）**，
 *       不是 UTC。若当成 UTC，冬令时/夏令时会让每个事件偏移 1~2 小时，
 *       而"包裹几点到"恰恰是客服最爱引用的字段；</li>
 *   <li>空格分隔：{@code 2026-09-20 10:00:00}（部分 DPD 老接口）→ 需先替换成 {@code T}。</li>
 * </ol>
 *
 * <p><b>解析不了就返回 null，绝不猜</b>：宁可让该节点没有时间，
 * 也不要写一个看起来合理但错的时间 —— 后者会被当成事实引用。
 */
public final class TrackingTimes {

    /** 承运商本地时区（DHL/DPD 的德国接口按欧洲中部时间返回）。 */
    private static final ZoneId CARRIER_ZONE = ZoneId.of("Europe/Berlin");

    private TrackingTimes() {
    }

    /**
     * 宽松解析：带时区 → 直接解析；不带时区 → 按 Europe/Berlin 解释；失败 → null。
     */
    public static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().replace(' ', 'T');
        // 形态 1：带偏移量或 Z
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地时间
        }
        // 形态 2/3：本地时间（秒 或 毫秒精度都可能出现，故用宽松裁剪）
        try {
            return LocalDateTime.parse(trimFraction(text)).atZone(CARRIER_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** 秒后的小数位超过 9 位时 {@code LocalDateTime.parse} 会失败，这里统一截到毫秒。 */
    private static String trimFraction(String text) {
        int dot = text.indexOf('.');
        if (dot < 0 || dot + 4 >= text.length()) {
            return text;
        }
        // 保留到毫秒（"." 后 3 位）
        return text.substring(0, dot + 4);
    }
}
