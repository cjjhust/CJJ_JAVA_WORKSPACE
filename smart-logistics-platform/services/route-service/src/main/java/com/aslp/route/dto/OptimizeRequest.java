package com.aslp.route.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * M3 路径优化请求体。
 *
 * <p>{@code POST /api/routes/optimize} 的入参；<b>允许整体省略</b>
 * （不传 body 时用 {@code VrpProblemFactory.demo()} 的内置演示数据，保持老调用方式可用）。
 *
 * <p>字段类型刻意用包装类型（{@code Double} / {@code Integer}）而非基本类型：
 * JSON 里缺字段会得到 {@code null}，从而能被校验器识别为“未提供/非法”，
 * 而基本类型会静默变成 {@code 0.0}（一个合法的经纬度或需求量），把错误藏起来。
 *
 * <p>示例：
 * <pre>
 * {
 *   "depot":   { "id": "Bruchsal-总仓", "lat": 49.1243, "lon": 8.5987 },
 *   "vehicles":[ { "id": "V-01", "capacity": 20 }, { "id": "V-02", "capacity": 20 } ],
 *   "deliveries":[ { "id": "VRP-D-01", "name": "Karlsruhe", "lat": 49.0069, "lon": 8.4037, "demand": 3 } ],
 *   "speedKmh": 60,
 *   "maxIterations": 2000
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptimizeRequest(
        Point depot,
        List<VehicleSpec> vehicles,
        List<DeliverySpec> deliveries,
        Double speedKmh,
        Integer maxIterations) {

    /** 仓库/车辆起始点。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(String id, Double lat, Double lon) {
    }

    /** 车辆：{@code capacity} 为载重维度 0 的容量。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VehicleSpec(String id, Double capacity) {
    }

    /** 配送作业：{@code demand} 为占用量（维度 0）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeliverySpec(String id, String name, Double lat, Double lon, Double demand) {
    }
}
