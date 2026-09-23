package com.aslp.route.tracking;

import java.util.List;
import java.util.Locale;

/**
 * 承运商状态码 → 统一状态 的翻译层（M3 归一化的唯一实现处）。
 *
 * <p><b>为什么要独立成类而不是写在各自的客户端里</b>：状态映射是最容易被漏改的地方 ——
 * 承运商新增一个状态码时，如果映射散落在两个客户端里，很容易只改一家。
 * 这里集中成两张表，并配 {@code TrackingStatusMapperTest} 把"认不出来的情况必须落到 UNKNOWN"钉死。
 *
 * <p><b>匹配策略：关键词包含（大小写无关），而不是精确相等</b>。
 * 承运商的状态码常有前后缀与大小写差异（DHL 的 {@code pre-transit}、
 * DPD 的 {@code IN_TRANSIT} / {@code inTransit} / {@code In Transit}）。
 * 精确匹配会天天漏；用包含匹配 + **先判异常再判终态**的顺序，才兼顾鲁棒与正确。
 *
 * <p><b>顺序很重要</b>：{@code DELIVERED} 必须早于 {@code IN_TRANSIT} 判断 ——
 * 例如 DPD 的 {@code "Delivered to neighbour"} 里同时含 "deliver"，
 * 而某些异常文案是 {@code "Delivery attempt failed"}；先判 EXCEPTION 才不会把失败当成功。
 */
public final class TrackingStatusMapper {

    private TrackingStatusMapper() {
    }

    /** 异常关键词：命中即 EXCEPTION（必须在终态判断之前）。 */
    private static final List<String> EXCEPTION_KEYWORDS = List.of(
            "fail", "error", "exception", "return", "refus", "reject",
            "lost", "damage", "undeliverable", "address issue", "not found at");

    /** 已妥投。 */
    private static final List<String> DELIVERED_KEYWORDS = List.of(
            "delivered", "zustellung erfolgreich", "zugestellt");

    /** 派送中（最后一公里）。 */
    private static final List<String> OUT_FOR_DELIVERY_KEYWORDS = List.of(
            "out for delivery", "out-for-delivery", "outfordelivery",
            "in zustellung", "zustellfahrzeug", "with delivery courier");

    /** 已揽收。 */
    private static final List<String> PICKED_UP_KEYWORDS = List.of(
            "pickup", "picked up", "collected", "abgeholt", "abholung");

    /** 运输途中（含中转与清关）。 */
    private static final List<String> IN_TRANSIT_KEYWORDS = List.of(
            "transit", "in_transit", "intransit", "departed", "arrived",
            "processed", "customs", "cleared", "hub", "sorting", "delivery depot",
            "unterwegs", "im zielpaketzentrum");

    /**
     * 尚未揽收（仅有电子面单信息）。
     *
     * <p><b>必须在 IN_TRANSIT 之前判定</b>：DHL 的"未揽收"状态码是 {@code pre-transit}，
     * 而它**包含子串 "transit"** —— 顺序写反会让"承运商还没收到包裹"显示成"运输途中"，
     * 客户就会以为包裹已经在路上了。（本类是先用测试跑出来的：见 TrackingStatusMapperTest。）
     */
    private static final List<String> CREATED_KEYWORDS = List.of(
            "pre-transit", "pretransit", "pre_transit", "electronic", "data received",
            "label", "angekündigt", "auftragsdaten");

    /**
     * DHL 状态码 → 统一状态。
     *
     * <p>DHL Unified Tracking 的 {@code status.statusCode} 通常落在
     * {@code pre-transit / transit / delivered / failure / unknown} 五类里，
     * 但 {@code status} 与 {@code description} 里常带更细的文案，因此两者一起参与匹配。
     */
    public static ShipmentState fromDhl(String statusCode, String statusText) {
        return normalize(join(statusCode, statusText));
    }

    /** DPD 状态码 → 统一状态（DPD 用 {@code status} + {@code label}/{@code scanDescription} 描述）。 */
    public static ShipmentState fromDpd(String statusCode, String description) {
        return normalize(join(statusCode, description));
    }

    /** 通用归一化：大小写无关的关键词包含匹配。 */
    static ShipmentState normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return ShipmentState.UNKNOWN;
        }
        String text = raw.toLowerCase(Locale.ROOT);
        if (containsAny(text, EXCEPTION_KEYWORDS)) {
            return ShipmentState.EXCEPTION;
        }
        if (containsAny(text, DELIVERED_KEYWORDS)) {
            return ShipmentState.DELIVERED;
        }
        if (containsAny(text, OUT_FOR_DELIVERY_KEYWORDS)) {
            return ShipmentState.OUT_FOR_DELIVERY;
        }
        if (containsAny(text, PICKED_UP_KEYWORDS)) {
            return ShipmentState.PICKED_UP;
        }
        // 顺序关键：pre-transit 含 "transit"，必须先判 CREATED 再判 IN_TRANSIT
        if (containsAny(text, CREATED_KEYWORDS)) {
            return ShipmentState.CREATED;
        }
        if (containsAny(text, IN_TRANSIT_KEYWORDS)) {
            return ShipmentState.IN_TRANSIT;
        }
        // 不认识就不猜：返回 UNKNOWN，原始状态码会随响应一起给出
        return ShipmentState.UNKNOWN;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + " " + b;
    }
}
