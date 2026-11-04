package com.aslp.inventory.event;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2-3 — order.sync 事件解析（与 order-service 的 {@code OrderEventPublisher#toPayload}
 * 契约对应，schemaVersion=1）。
 *
 * <p>字段集：orderId / product / warehouse / status / errorTag / schemaVersion。
 * 这里只取预占所需字段（orderId, warehouse, status → 数量恒为 1）：
 * <b>事件契约故意收窄</b>——库存预占只关心「哪个订单、哪个仓、是否可履约」，
 * 其余字段对下游是噪音，进契约反而会在上游演进时把下游炸掉。
 *
 * <p>预占数量规则：每事件预占 1 件（当前业务形态：FBA 退货换标 / 一件代发均为单品单）。
 * 数量 > 1 的订单属未来契约演进（schemaVersion=2），消费侧按 1 处理并 WARN。
 */
public record OrderSyncEvent(String orderId, String product, String warehouse, String status, String errorTag) {

    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PRODUCT = Pattern.compile("\"product\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern WAREHOUSE = Pattern.compile("\"warehouse\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ERROR_TAG = Pattern.compile("\"errorTag\"\\s*:\\s*(null|\"[^\"]*\")");

    /** 每事件预占数量（单品单业务形态）。 */
    public int quantity() {
        return 1;
    }

    /**
     * 库存扣减键。当前事件契约（schemaVersion=1）不含真实 SKU——订单域与库存域
     * 尚无商品映射表，演示形态下以商品描述字符串作为库存键。
     * 真实接入时事件契约演进到 schemaVersion=2 携带 SKU，本方法改为直接返回。
     */
    public String sku() {
        return product;
    }

    /** 是否可进入履约预占（异常标签为空的订单才预占，与 HTTP 链路口径一致）。 */
    public boolean isNormal() {
        return errorTag == null || errorTag.isBlank();
    }

    public static OrderSyncEvent parse(String payload) throws BadEventException {
        if (payload == null || payload.isBlank()) {
            throw new BadEventException("empty payload");
        }
        String orderId = extract(ORDER_ID, payload);
        if (orderId == null) {
            throw new BadEventException("missing orderId: " + payload);
        }
        String warehouse = extract(WAREHOUSE, payload);
        if (warehouse == null) {
            throw new BadEventException("missing warehouse: " + payload);
        }
        String status = extract(STATUS, payload);
        if (status == null) {
            throw new BadEventException("missing status: " + payload);
        }
        return new OrderSyncEvent(orderId,
                extract(PRODUCT, payload),
                warehouse,
                status,
                extractErrorTag(payload));
    }

    private static String extract(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String extractErrorTag(String s) {
        Matcher m = ERROR_TAG.matcher(s);
        if (!m.find()) {
            return null;
        }
        String g = m.group(1);
        return "null".equals(g) ? null : g.replace("\"", "");
    }

    /** 毒事件（无法解析 / 缺关键字段）：消费侧跳过重试直达 DLT。 */
    public static class BadEventException extends Exception {
        public BadEventException(String message) {
            super(message);
        }
    }
}
