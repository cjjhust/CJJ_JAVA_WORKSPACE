package com.aslp.route.tracking;

/**
 * 支持的承运商（M3 尾程）。
 *
 * <p>枚举名同时是对外 API 的参数值（{@code ?carrier=DHL}），因此不做重命名 ——
 * 改枚举名等于改公开契约。中德两地实际合作方就这两家（DHL 德国境内、DPD 欧洲跨境），
 * 需要加 GLS 时在这里补一个常量并在 {@code TrackingStatusMapper} 里补映射表即可。
 */
public enum Carrier {
    /** DHL（Deutsche Post DHL）—— 德国境内尾程主力。 */
    DHL,
    /** DPD —— 欧洲跨境与部分分区派送。 */
    DPD
}
