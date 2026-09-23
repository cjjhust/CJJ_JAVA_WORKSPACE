package com.aslp.route.tracking;

import java.time.Instant;
import java.util.List;

/**
 * 「一单到底」的查询结果（对外统一结构，与承运商无关）。
 *
 * <p>字段设计要点：
 * <ul>
 *   <li>{@code state} 是**归一化**后的状态（前端只认它）；{@code carrierStatus} 保留原文，
 *       两者一起给，既好渲染又不丢信息。</li>
 *   <li>{@code events} 按时间<b>倒序</b>（最新在前）：面客界面 99% 的场景是"现在到哪了"，
 *       正序列表每次都要前端反着读。</li>
 *   <li>{@code stale}：本结果为缓存命中时为 true —— 让调用方知道"这不是刚刚问来的"，
 *       对时效敏感的展示（如"刚刚更新"）不该说谎。</li>
 *   <li>{@code estimatedDelivery} 允许 null（承运商不一定给；编一个假 ETA 比不给更糟）。</li>
 * </ul>
 */
public record TrackingView(
        String trackingNumber,
        Carrier carrier,
        ShipmentState state,
        String carrierStatus,
        String statusDescription,
        String lastLocation,
        Instant lastEventAt,
        Instant estimatedDelivery,
        List<TrackingEvent> events,
        /** 命中本地缓存（见 {@code aslp.tracking.cache-ttl}）。 */
        boolean stale) {

    /**
     * 复制一份并改写缓存标记。
     *
     * <p>record 是不可变的，缓存里存的是 {@code stale=false} 的原件，
     * 命中时复制一份标成 {@code true} —— 这样"这份数据是不是刚问来的"对调用方永远可信，
     * 也不会因为改写共享对象而串味。
     */
    public TrackingView withStale(boolean value) {
        return new TrackingView(trackingNumber, carrier, state, carrierStatus, statusDescription,
                lastLocation, lastEventAt, estimatedDelivery, events, value);
    }

    /** 事件列表截断（面客只需要近期节点，承运商常给整条历史）。 */
    public TrackingView withMaxEvents(int max) {
        if (max <= 0 || events.size() <= max) {
            return this;
        }
        return new TrackingView(trackingNumber, carrier, state, carrierStatus, statusDescription,
                lastLocation, lastEventAt, estimatedDelivery, List.copyOf(events.subList(0, max)), stale);
    }
}
