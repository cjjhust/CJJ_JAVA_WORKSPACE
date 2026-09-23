package com.aslp.route.tracking;

/**
 * 承运商追踪客户端（M3 一单到底）。
 *
 * <p>与 M1 的 {@code OrderPullStrategy} 同一套设计：**接口统一、实现按承运商分开、可开关 Mock**。
 * 新增 GLS 时只要实现本接口并注册成 bean，{@link TrackingService} 无需改动
 * （它按 {@link #carrier()} 自动建立映射，不在服务层写 instanceof 判断）。
 *
 * <p>契约（三种失败必须区分，它们的对外 HTTP 状态码与"该不该重试"完全不同）：
 * <ul>
 *   <li>成功 → 返回归一化后的 {@link TrackingView}（{@code stale=false}，缓存标记由服务层设置）；</li>
 *   <li>查无此单 → {@link TrackingNotFoundException}（→ 404：让用户核对单号）；</li>
 *   <li>对方/网络故障（5xx、429、超时）→ {@link TrackingUnavailableException}（→ 503：稍后再试）；</li>
 *   <li>被拒绝（其他 4xx）→ {@link TrackingClientException}（→ 502：我们自己看日志）。</li>
 * </ul>
 */
public interface TrackingClient extends TrackingFetcher {

    /** 本实现负责的承运商（服务层据此自动注册，避免脆弱的 instanceof 判断）。 */
    Carrier carrier();
}
