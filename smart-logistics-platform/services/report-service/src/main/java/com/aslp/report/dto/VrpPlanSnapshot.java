package com.aslp.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * VRP 求解结果快照（报表侧视图）。
 *
 * <p>字段与 route-service 的 {@code VrpPlan} 回执一一对应（Jackson 按名反序列化，
 * {@code ignoreUnknown} 保证上游加字段不会破坏看板）。
 * 报表只做搬运与展示，不解释求解语义 —— 口径单一维护在 route-service。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VrpPlanSnapshot(
        boolean feasible,
        String message,
        double totalDistanceKm,
        double totalLoad,
        int routeCount,
        int stopCount,
        List<String> unassignedJobIds,
        long elapsedMs,
        List<Route> routes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(
            String routeId,
            String vehicleId,
            String depotId,
            double distanceKm,
            double load,
            List<Stop> stops) {
    }

    /** {@code kind}: delivery（单向卸货）/ pickup（PDP 互调装货）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stop(
            int sequence,
            String jobId,
            String name,
            double lat,
            double lon,
            double demand,
            double arrivalHours,
            String kind) {
    }
}
