package com.aslp.route.tracking;

/**
 * 「拉取一份轨迹」的最小能力（M3 追踪）。
 *
 * <p>把"能查"与"我是哪家承运商"拆成两个接口（{@link TrackingClient} 继承本接口并补上 {@code carrier()}）
 * 是为了让 Mock 实现也能被统一调用，却不必谎报自己属于某个承运商 ——
 * {@code TrackingService} 因此不需要写 {@code instanceof MockTrackingClient} 这类脆弱的判断。
 */
@FunctionalInterface
public interface TrackingFetcher {

    /**
     * 查询单个包裹的最新轨迹。
     *
     * @param carrier        目标承运商（真实实现需校验与自身一致，避免装配错误沉默生效）
     * @param trackingNumber 承运商单号（已由服务层去空白）
     */
    TrackingView fetch(Carrier carrier, String trackingNumber);
}
