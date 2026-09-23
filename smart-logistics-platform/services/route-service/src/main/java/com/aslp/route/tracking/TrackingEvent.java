package com.aslp.route.tracking;

import java.time.Instant;

/**
 * 单个物流节点（扫描事件）。
 *
 * @param state          归一化状态（由 {@link TrackingStatusMapper} 从承运商状态码翻译）
 * @param carrierStatus  承运商原始状态码/描述 —— 保留它才能在出问题时和承运商客服对得上话
 * @param description    事件描述（原文，不做翻译：德语/英语原文比机翻更不容易误导）
 * @param location       发生地（承运商给的原文）
 * @param occurredAt     发生时间（解析失败时为 null —— 宁可缺时间也不要编一个）
 */
public record TrackingEvent(
        ShipmentState state,
        String carrierStatus,
        String description,
        String location,
        Instant occurredAt) {
}
