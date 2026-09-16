package com.aslp.route.dto;

import java.util.List;

/**
 * M3 路径优化结果（jsprit VRP 求解输出）。
 *
 * <p>字段与 {@code test-data/mock-test-data.json} 的 {@code routeResults}
 * （{@code vehicleId} / {@code stops} / {@code distanceKm}）保持一致，
 * 便于直接喂给 ECharts 看板或运费引擎。
 *
 * <p>距离单位是 <b>km</b>（由 {@code HaversineCostModel} 以真实大圆距离计算），
 * 已四舍五入到 0.1 km。{@code feasible=false} 时 {@code message} 为
 * {@link #NO_FEASIBLE_ROUTE_MESSAGE}，{@code routes} 为空，
 * 全部作业列入 {@code unassignedJobIds}。
 */
public record VrpPlan(
        boolean feasible,
        String message,
        double totalDistanceKm,
        double totalLoad,
        int routeCount,
        int stopCount,
        List<String> unassignedJobIds,
        long elapsedMs,
        List<Route> routes) {

    /** 无可行解时的哨兵文案（历史契约，前端与冒烟脚本据此判断）。 */
    public static final String NO_FEASIBLE_ROUTE_MESSAGE = "无可行路线";

    /** 单条配送路线。{@code distanceKm} 含空车出仓与返回总仓的里程。 */
    public record Route(
            String routeId,
            String vehicleId,
            String depotId,
            double distanceKm,
            double load,
            List<Stop> stops) {
    }

    /** 单个配送作业（含顺序与预计到达时刻）。 */
    public record Stop(
            int sequence,
            String jobId,
            String name,
            double lat,
            double lon,
            double demand,
            double arrivalHours) {
    }
}
