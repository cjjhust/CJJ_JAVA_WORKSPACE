package com.aslp.route.tracking;

/**
 * 归一化后的包裹状态（M3「一单到底」的统一口径）。
 *
 * <p><b>为什么必须有这一层</b>：DHL 与 DPD 的状态码体系完全不同
 * （DHL 用 {@code pre-transit/transit/delivered/failure/unknown}，
 * DPD 用 {@code PICKUP/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/...} 且大小写不统一）。
 * 如果让前端各自判断"哪家承运商的哪个字符串代表已妥投"，
 * 那么每接一家新承运商就要改一次前端 —— 所以归一化只做一次，做在服务端。
 *
 * <p>{@link #UNKNOWN} 刻意保留：**遇到没见过的状态码时不许猜**。
 * 猜错会把"包裹异常"显示成"运输中"，用户就不去查了 —— 宁可显示"状态未知（原始状态码：xxx）"。
 * 原始状态码始终随 {@link TrackingView#carrierStatus()} 一起返回，信息不丢失。
 */
public enum ShipmentState {

    /** 已下单/已生成面单，承运商尚未揽收。 */
    CREATED,
    /** 承运商已揽收。 */
    PICKED_UP,
    /** 运输途中（含中转、清关）。 */
    IN_TRANSIT,
    /** 派送中（最后一公里）。 */
    OUT_FOR_DELIVERY,
    /** 已妥投。 */
    DELIVERED,
    /** 异常（地址错误、拒收、丢失、退回等）—— 需要人工介入的状态。 */
    EXCEPTION,
    /** 无法识别的承运商状态码（原始值见 carrierStatus）。 */
    UNKNOWN
}
