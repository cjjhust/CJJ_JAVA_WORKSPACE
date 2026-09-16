package com.aslp.route.engine;

import com.aslp.route.dto.OptimizeRequest;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Coordinate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 {@link OptimizeRequest}（对外契约）翻译成 jsprit 的 {@link VehicleRoutingProblem}（内部模型），
 * 并在这里集中做入参校验与求解参数解析。
 *
 * <p>放在单独一层的原因：jsprit 的构建器链很长且会抛各种底层异常，
 * 让它直接出现在 Controller 里既难读、也无法给出“哪个字段错了”的中文提示。
 *
 * <p>校验要点（均返回 HTTP 400，见 {@code RouteController}）：
 * <ul>
 *   <li>坐标必须成对出现且在合法范围内 —— 缺字段时 {@code Double} 为 {@code null}，
 *       若用基本类型会静默变成 {@code 0.0}（几内亚湾的合法坐标），错误会被藏起来；</li>
 *   <li>容量 / 需求量必须为<b>正整数</b>：jsprit 1.8 的 {@code Capacity} 维度是 {@code int}
 *       （{@code addSizeDimension(int, int)}），传 2.5 会被静默截断成 2；</li>
 *   <li>作业 id 必须唯一且非空：它是「未指派作业」回执里的唯一标识。</li>
 * </ul>
 */
@Component
public class VrpProblemFactory {

    /** 默认平均车速（km/h）。 */
    public static final double DEFAULT_SPEED_KMH = HaversineCostModel.DEFAULT_SPEED_KMH;

    /** 默认迭代上限：jsprit「Schrimpf 破坏-重建」算法的默认值，实测 20 个作业约 1~2 秒。 */
    public static final int DEFAULT_MAX_ITERATIONS = 2000;

    /** 迭代下限：低于此值解质量没有意义。 */
    public static final int MIN_MAX_ITERATIONS = 100;

    /** 迭代上限：防止单次 HTTP 请求长时间占住线程（5000 次 ≈ 数十秒级）。 */
    public static final int MAX_MAX_ITERATIONS = 5000;

    /** 车速上限（km/h）：欧洲卡车限速 ~90，超过 130 基本是录入错误。 */
    public static final double MAX_SPEED_KMH = 130.0;

    /**
     * 求解输入：jsprit 问题 + 求解器参数（已解析并校验过）。
     *
     * @param problem       jsprit 问题（已绑定 {@link HaversineCostModel}）
     * @param maxIterations 迭代上限
     * @param speedKmh      平均车速（km/h，用于折算行驶时间）
     */
    public record Spec(VehicleRoutingProblem problem, int maxIterations, double speedKmh) {
    }

    /**
     * 校验并转换请求体。
     *
     * @param request 请求体；{@code null}（未传 body）时回落 {@link #demo()}
     * @throws IllegalArgumentException 校验不通过，消息里列出全部问题字段
     */
    public Spec fromRequest(OptimizeRequest request) {
        if (request == null) {
            return demo();
        }

        List<String> errors = new ArrayList<>();

        // ---- depot ----
        OptimizeRequest.Point depot = request.depot();
        if (depot == null) {
            errors.add("depot 必填（车辆起始/返回仓库）");
        } else {
            requireCoordinate("depot", depot.lat(), depot.lon(), errors);
        }
        String depotId = depot != null && depot.id() != null && !depot.id().isBlank() ? depot.id() : "DEPOT";
        // 坐标缺任一项时不要自动拆箱（会 NPE）：让错误走校验分支，统一汇总成 400
        boolean depotHasCoordinate = depot != null && depot.lat() != null && depot.lon() != null;
        Location depotLocation = depotHasCoordinate ? location(depotId, depot.lat(), depot.lon()) : null;

        // ---- vehicles ----
        List<OptimizeRequest.VehicleSpec> vehicles = request.vehicles();
        List<VehicleImpl> builtVehicles = new ArrayList<>();
        Set<String> vehicleIds = new HashSet<>();
        if (vehicles == null || vehicles.isEmpty()) {
            errors.add("vehicles 至少需要 1 辆车");
        } else {
            for (int i = 0; i < vehicles.size(); i++) {
                OptimizeRequest.VehicleSpec v = vehicles.get(i);
                String id = v.id() == null || v.id().isBlank() ? String.format("V-%02d", i + 1) : v.id();
                if (!vehicleIds.add(id)) {
                    errors.add("车辆 id 重复：" + id);
                    continue;
                }
                Integer capacity = requirePositiveInt("vehicles[" + i + "].capacity", v.capacity(), errors);
                if (capacity != null && depotLocation != null) {
                    builtVehicles.add(vehicle(id, capacity, depotLocation));
                }
            }
        }

        // ---- deliveries ----
        List<OptimizeRequest.DeliverySpec> deliveries = request.deliveries();
        List<Service> builtJobs = new ArrayList<>();
        Set<String> jobIds = new HashSet<>();
        if (deliveries == null || deliveries.isEmpty()) {
            errors.add("deliveries 至少需要 1 个配送作业");
        } else {
            for (int i = 0; i < deliveries.size(); i++) {
                OptimizeRequest.DeliverySpec d = deliveries.get(i);
                String label = "deliveries[" + i + "]";
                if (d.id() == null || d.id().isBlank()) {
                    errors.add(label + ".id 必填（用于回执未指派作业）");
                    continue;
                }
                if (!jobIds.add(d.id())) {
                    errors.add("作业 id 重复：" + d.id());
                    continue;
                }
                requireCoordinate(label, d.lat(), d.lon(), errors);
                Integer demand = requirePositiveInt(label + ".demand", d.demand(), errors);
                if (d.lat() != null && d.lon() != null && demand != null) {
                    builtJobs.add(job(d.id(), d.name(), d.lat(), d.lon(), demand));
                }
            }
        }

        double speedKmh = resolveSpeedKmh(request.speedKmh(), errors);
        int maxIterations = resolveMaxIterations(request.maxIterations(), errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("路径优化入参校验失败：" + String.join("；", errors));
        }

        VehicleRoutingProblem.Builder builder = VehicleRoutingProblem.Builder.newInstance()
                .setRoutingCost(new HaversineCostModel(speedKmh));
        builtVehicles.forEach(builder::addVehicle);
        builtJobs.forEach(builder::addJob);
        return new Spec(builder.build(), maxIterations, speedKmh);
    }

    /**
     * 内置演示问题：Bruchsal 总仓 → Karlsruhe / Frankfurt / Düsseldorf / Mönchengladbach，
     * 2 辆载重 20 的厢式车（总需求 14，故最优解通常是 1 辆车跑完）。
     *
     * <p>让 {@code curl -X POST .../api/routes/optimize}（不带 body）依旧可用，
     * 保持 {@code readme.md} §4 的历史调用方式。
     */
    public Spec demo() {
        return fromRequest(new OptimizeRequest(
                new OptimizeRequest.Point("Bruchsal-总仓", 49.1243, 8.5987),
                List.of(
                        new OptimizeRequest.VehicleSpec("V-01", 20.0),
                        new OptimizeRequest.VehicleSpec("V-02", 20.0)),
                List.of(
                        new OptimizeRequest.DeliverySpec("VRP-D-01", "Karlsruhe", 49.0069, 8.4037, 3.0),
                        new OptimizeRequest.DeliverySpec("VRP-D-02", "Frankfurt", 50.1109, 8.6821, 5.0),
                        new OptimizeRequest.DeliverySpec("VRP-D-03", "Düsseldorf", 51.2277, 6.7735, 2.0),
                        new OptimizeRequest.DeliverySpec("VRP-D-04", "Mönchengladbach", 51.1805, 6.4428, 4.0)),
                null,
                null));
    }

    /**
     * 车辆：起终点同为一个仓库，即「出仓 → 配送 → 回仓」闭环。
     *
     * <p>jsprit 未显式设 {@code endLocation} 时默认等于 {@code startLocation}；
     * 路线里程统计也因此包含返程（见 {@code VrpRouteService#toPlan}）。
     */
    private static VehicleImpl vehicle(String id, int capacity, Location depot) {
        return VehicleImpl.Builder.newInstance(id)
                .setStartLocation(depot)
                .setType(VehicleTypeImpl.Builder.newInstance(id + "-type")
                        .addCapacityDimension(0, capacity)
                        .build())
                .build();
    }

    private static Service job(String id, String name, double lat, double lon, int demand) {
        return Service.Builder.newInstance(id)
                .setName(name == null || name.isBlank() ? id : name)
                .setLocation(location(id, lat, lon))
                .addSizeDimension(0, demand)
                .build();
    }

    /**
     * 构造带可读 id 的位置。
     *
     * <p>不用 {@code Location.newInstance(lat, lon)}：那样 id 会变成 {@code [x=49.1][y=8.5]}，
     * 回执里就无法给出「停靠点是哪个订单 / 哪个仓」。
     *
     * <p><b>约定：第一个参数纬度、第二个经度</b>（与 {@link HaversineCostModel} 一致）。
     */
    private static Location location(String id, double lat, double lon) {
        return Location.Builder.newInstance()
                .setId(id)
                .setCoordinate(Coordinate.newInstance(lat, lon))
                .build();
    }

    private double resolveSpeedKmh(Double speedKmh, List<String> errors) {
        if (speedKmh == null) {
            return DEFAULT_SPEED_KMH;
        }
        if (!(speedKmh > 0) || !Double.isFinite(speedKmh) || speedKmh > MAX_SPEED_KMH) {
            errors.add("speedKmh 必须在 0 ~ " + (int) MAX_SPEED_KMH + " 之间（km/h）：" + speedKmh);
            return DEFAULT_SPEED_KMH;
        }
        return speedKmh;
    }

    private int resolveMaxIterations(Integer maxIterations, List<String> errors) {
        if (maxIterations == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        if (maxIterations < MIN_MAX_ITERATIONS || maxIterations > MAX_MAX_ITERATIONS) {
            errors.add("maxIterations 必须在 " + MIN_MAX_ITERATIONS + " ~ " + MAX_MAX_ITERATIONS
                    + " 之间：" + maxIterations);
            return DEFAULT_MAX_ITERATIONS;
        }
        return maxIterations;
    }

    /** 坐标必须成对出现且在合法范围内。 */
    private static void requireCoordinate(String label, Double lat, Double lon, List<String> errors) {
        if (lat == null || lon == null) {
            errors.add(label + " 需要同时提供 lat 与 lon");
            return;
        }
        if (lat < -90 || lat > 90) {
            errors.add(label + ".lat 必须在 -90 ~ 90：" + lat);
        }
        if (lon < -180 || lon > 180) {
            errors.add(label + ".lon 必须在 -180 ~ 180：" + lon);
        }
    }

    /** 容量 / 需求量必须是正整数（jsprit 的维度是 int，小数会被静默截断）。 */
    private static Integer requirePositiveInt(String label, Double value, List<String> errors) {
        if (value == null) {
            errors.add(label + " 必填");
            return null;
        }
        if (!(value > 0) || value != Math.rint(value) || value > Integer.MAX_VALUE) {
            errors.add(label + " 必须是正整数（jsprit 1.8 的容量维度为 int）：" + value);
            return null;
        }
        return (int) Math.rint(value);
    }
}
