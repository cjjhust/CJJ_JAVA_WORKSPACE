package com.aslp.route.service;

import com.aslp.route.dto.VrpPlan;
import com.aslp.route.engine.GeoDistance;
import com.aslp.route.engine.VrpProblemFactory;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.box.Jsprit;
import com.graphhopper.jsprit.core.problem.Capacity;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Job;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.VehicleRoute;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.Solutions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * M3 路径优化求解器（jsprit 1.8 的薄封装）。
 *
 * <h3>缺陷 #27：引擎原先根本跑不起来（P1-2b 修复）</h3>
 * 原实现是：
 * <pre>
 * SearchStrategyManager strategy = new SearchStrategyManager();          // ← 空的策略注册表
 * VehicleRoutingAlgorithm algorithm = new VehicleRoutingAlgorithm(problem, strategy);
 * algorithm.searchSolutions();                                           // ← 必抛异常
 * </pre>
 * {@code SearchStrategyManager} 只是「策略容器」，不是策略本身。jsprit 需要在其构造过程中
 * （{@code SchrimpfFactory} / {@code Jsprit.Builder}）把「破坏-重建（ruin &amp; recreate）」策略
 * 与核心状态、约束一起注册进去；不注册就一定在 {@code getRandomStrategy()} 抛
 * {@code IllegalStateException: no search-strategy found}（已用独立程序复现确认）。
 * 端到端之所以长期没暴露，是因为 {@code RouteController.optimize()} 当时是桩方法，从未调用本服务。
 *
 * <p>修复方式：改用官方高层入口 {@link Jsprit.Builder#buildAlgorithm()}，
 * 它把初始解构造（{@code InsertionInitialSolutionFactory}）、搜索策略集合、状态与约束、
 * 终止条件（迭代上限）一次性装配好。
 *
 * <h3>顺带修掉的第二个缺陷：距离量纲</h3>
 * 即使算法能跑，jsprit 默认的欧氏距离用的是<b>坐标单位</b>——我们的坐标是经纬度，于是
 * 「里程」的单位是<b>度</b>（Bruchsal→Karlsruhe 算出 0.19），无法用于计费与 ETA。
 * 因此问题侧统一绑定 {@code HaversineCostModel}（真实大圆距离，单位 km），
 * 见 {@code VrpProblemFactory}。
 *
 * <h3>可复现性</h3>
 * 固定随机种子 {@link #RANDOM_SEED}：同一问题与同一迭代数必须产出同一结果，
 * 否则冒烟脚本的端到端断言与单测会间歇性失败。
 *
 * <h3>P1-1 可观测性</h3>
 * 求解是 M3 最重的计算路径（演示问题 ~200ms，复杂问题秒级），因此单独打点：
 * <ul>
 *   <li>{@code aslp_vrp_solve_seconds{feasible}} —— 求解耗时（Timer，可算 P95）；</li>
 *   <li>{@code aslp_vrp_distance_km} —— 每次求解出的总里程分布（DistributionSummary）。</li>
 * </ul>
 * 有了这两个指标，「M3 变慢了」与「解变差了（里程变长）」能被区分开。
 */
@Service
public class VrpRouteService {

    /** P1-1：求解耗时 Timer（Prometheus: aslp_vrp_solve_seconds）。 */
    public static final String METRIC_SOLVE = "aslp.vrp.solve";

    /** P1-1：求解里程分布 Summary（Prometheus: aslp_vrp_distance_km）。 */
    public static final String METRIC_DISTANCE = "aslp.vrp.distance";

    /** 固定随机种子（jsprit 的破坏-重建过程带随机性）。 */
    public static final long RANDOM_SEED = 20260916L;

    private final MeterRegistry meterRegistry;

    public VrpRouteService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 用默认迭代上限求解。 */
    public VrpPlan solve(VehicleRoutingProblem problem) {
        return solve(problem, VrpProblemFactory.DEFAULT_MAX_ITERATIONS);
    }

    /**
     * 求解 VRP 并转成对外计划。
     *
     * @param problem       jsprit 问题（由 {@code VrpProblemFactory} 构建，已绑定 km 成本模型）
     * @param maxIterations 迭代上限（至少 1；取值范围由工厂校验）
     */
    public VrpPlan solve(VehicleRoutingProblem problem, int maxIterations) {
        long startedAt = System.currentTimeMillis();
        VehicleRoutingAlgorithm algorithm = Jsprit.Builder.newInstance(problem)
                .setRandom(new Random(RANDOM_SEED))
                .setProperty(Jsprit.Parameter.ITERATIONS, String.valueOf(Math.max(1, maxIterations)))
                .buildAlgorithm();
        VehicleRoutingProblemSolution best = Solutions.bestOf(algorithm.searchSolutions());
        VrpPlan plan = toPlan(problem, best, System.currentTimeMillis() - startedAt);

        // P1-1：无论有解无解都要打点（无解次数突增往往是需求/运力数据出了问题）
        meterRegistry.timer(METRIC_SOLVE, "feasible", String.valueOf(plan.feasible()))
                .record(Duration.ofMillis(plan.elapsedMs()));
        if (plan.feasible()) {
            meterRegistry.summary(METRIC_DISTANCE).record(plan.totalDistanceKm());
        }
        return plan;
    }

    /**
     * 把 jsprit 的解翻译为 {@link VrpPlan}。
     *
     * <p><b>「无可行路线」的判定必须看路线数，而不是看解是否为 null</b>：
     * 实测（jsprit 1.8）即使是空问题或运力完全不足，{@code Solutions.bestOf(...)} 也会返回一个
     * <b>非 null</b> 的「0 条路线、作业全部未指派」的解。原实现的
     * {@code best != null ? best.toString() : "无可行路线"} 分支因此永远走不到，
     * 而 {@code toString()} 只会给出 {@code [costs=0.0][routes=0][unassigned=0]} 这种对调用方无用的字符串。
     */
    private VrpPlan toPlan(VehicleRoutingProblem problem, VehicleRoutingProblemSolution best, long elapsedMs) {
        List<String> unassigned = (best == null
                ? problem.getJobs().values()
                : best.getUnassignedJobs())
                .stream()
                .map(Job::getId)
                .sorted()
                .toList();

        if (best == null || best.getRoutes().isEmpty()) {
            return new VrpPlan(false, VrpPlan.NO_FEASIBLE_ROUTE_MESSAGE, 0.0, 0.0,
                    0, 0, unassigned, elapsedMs, List.of());
        }

        List<VrpPlan.Route> routes = new ArrayList<>();
        double totalKm = 0.0;
        double totalLoad = 0.0;
        int stopCount = 0;
        int routeSequence = 1;

        for (VehicleRoute route : best.getRoutes()) {
            Location depot = route.getStart().getLocation();
            Location previous = depot;
            List<VrpPlan.Stop> stops = new ArrayList<>();
            double km = 0.0;
            double load = 0.0;
            int sequence = 1;

            for (TourActivity activity : route.getActivities()) {
                Location current = activity.getLocation();
                km += GeoDistance.km(lat(previous), lon(previous), lat(current), lon(current));
                double demand = demandOf(activity);
                stops.add(new VrpPlan.Stop(sequence++, jobIdOf(activity), nameOf(activity),
                        lat(current), lon(current), demand, GeoDistance.round2(activity.getArrTime())));
                load += demand;
                previous = current;
            }
            // 返仓里程：jsprit 未设 endLocation 时默认等于 startLocation，路线是闭环
            km += GeoDistance.km(lat(previous), lon(previous), lat(depot), lon(depot));

            routes.add(new VrpPlan.Route(
                    String.format("VRP-%02d", routeSequence++),
                    route.getVehicle().getId(),
                    depot.getId(),
                    GeoDistance.roundKm(km),
                    load,
                    List.copyOf(stops)));
            totalKm += km;
            totalLoad += load;
            stopCount += stops.size();
        }

        String message = String.format("已生成 %d 条配送路线，总里程 %.1f km", routes.size(), totalKm);
        if (!unassigned.isEmpty()) {
            message += String.format("；%d 个作业因运力不足未指派", unassigned.size());
        }
        return new VrpPlan(true, message, GeoDistance.roundKm(totalKm), totalLoad,
                routes.size(), stopCount, unassigned, elapsedMs, routes);
    }

    /** 活动的作业 id。{@code TourActivity} 接口本身没有 {@code getJob()}，需走 {@code JobActivity} 子接口。 */
    private static String jobIdOf(TourActivity activity) {
        return activity instanceof TourActivity.JobActivity jobActivity
                ? jobActivity.getJob().getId()
                : activity.getName();
    }

    /** 展示名优先用作业名（如 "Karlsruhe"）；{@code activity.getName()} 只返回 "service" 这种活动类型名。 */
    private static String nameOf(TourActivity activity) {
        if (activity instanceof TourActivity.JobActivity jobActivity) {
            String name = jobActivity.getJob().getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
            return jobActivity.getJob().getId();
        }
        return activity.getName();
    }

    /** 该停靠点的占用量（jsprit 的容量维度 0）。 */
    private static double demandOf(TourActivity activity) {
        Capacity size = activity.getSize();
        return size != null && size.getNuOfDimensions() > 0 ? size.get(0) : 0.0;
    }

    /** 纬度：约定 {@code x} 为纬度（与 {@code HaversineCostModel}、{@code GeoDistance} 一致）。 */
    private static double lat(Location location) {
        return location.getCoordinate().getX();
    }

    /** 经度：约定 {@code y} 为经度。 */
    private static double lon(Location location) {
        return location.getCoordinate().getY();
    }
}
