package com.aslp.report.client;

import com.aslp.report.dto.VrpPlanSnapshot;

/**
 * VRP 求解结果的数据源抽象（M3 路线可视化）。
 *
 * <p>与 {@link OrderStatsSource} 同一模式：微服务拓扑走 HTTP client，
 * 单体拓扑由 app-monolith 提供进程内实现，聚合层代码两种拓扑完全一致。
 */
public interface VrpPlanSource {

    String getSource();

    /**
     * 求解内置演示问题（Bruchsal 总仓 → 4 城，PDP 与单向配送并存）。
     *
     * @throws ReportSourceUnavailableException 连接失败 / 超时 / 5xx
     */
    VrpPlanSnapshot fetch();
}
