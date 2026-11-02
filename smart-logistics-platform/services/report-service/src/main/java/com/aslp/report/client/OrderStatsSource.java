package com.aslp.report.client;

import com.aslp.report.dto.OrderStatsSnapshot;

/**
 * 订单统计数据源抽象（单体共存改造，P2-7）。
 *
 * <p>报表聚合只依赖此接口，不关心数据是走 HTTP 还是进程内调用：
 * <ul>
 *   <li><b>微服务拓扑</b>（默认）：{@link OrderStatsClient} 通过 HTTP 调 order-service；</li>
 *   <li><b>单体拓扑</b>（profile=monolith）：{@code LocalOrderStatsSource}（app-monolith 模块）
 *       直接注入 order 模块的 Repository 进程内取数，零网络开销。</li>
 * </ul>
 *
 * <p>契约：实现方在数据源不可用时必须抛 {@link ReportSourceUnavailableException}，
 * 由聚合方统一降级 —— 两种拓扑的失败语义因此完全一致。
 */
public interface OrderStatsSource {

    /** 数据源标识：会原样出现在报表响应的 {@code unavailable} 列表里。 */
    String getSource();

    /**
     * 拉取订单统计。
     *
     * @throws ReportSourceUnavailableException 数据源不可用（连接失败 / 超时 / 4xx / 5xx / 空响应体）
     */
    OrderStatsSnapshot fetch();
}
