package com.aslp.route.service;

import com.aslp.route.dto.OptimizeRequest;
import com.aslp.route.dto.VrpPlan;
import com.aslp.route.engine.HaversineCostModel;
import com.aslp.route.engine.VrpProblemFactory;
import com.graphhopper.jsprit.core.algorithm.SearchStrategyManager;
import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleTypeImpl;
import com.graphhopper.jsprit.core.util.Coordinate;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 路径优化求解测试：{@link VrpRouteService} 是 jsprit 求解器的薄封装。
 *
 * <p><b>本类的前身是缺陷 #27 的“证据”</b>：上一轮生成测试时发现
 * {@code IllegalStateException: no search-strategy found}（裸 {@code SearchStrategyManager} 没注册策略），
 * 当时按流程只标记 {@code @Disabled} 并记录根因，未改生产代码。
 * P1-2b 修复后 {@code @Disabled} 已移除，并新增
 * {@link #bareSearchStrategyManagerStillFails()} 把「旧写法为什么必错」变成长期回归证据。
 *
 * <p>断言策略：只断言几何/契约层面的确定性事实（往返里程、闭环、无解语义、可复现性），
 * 不断言具体的搜索顺序与迭代次数 —— 那属于算法内部行为，会随 jsprit 版本变化。
 */
class VrpRouteServiceTest {

    private static final VrpProblemFactory FACTORY = new VrpProblemFactory();

    /** P1-1：内存注册表——断言指标而不依赖 Prometheus。 */
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /** P1-3：与 Boot 自动配置一致——Observation 经 MeterObservationHandler 产出指标，并产出 span。 */
    private final ObservationRegistry observationRegistry = observationRegistry(meterRegistry);

    private final VrpRouteService service = new VrpRouteService(meterRegistry, observationRegistry);

    // Bruchsal 总仓 与 Karlsruhe（真实坐标，纬度, 经度）
    private static final double DEPOT_LAT = 49.1243, DEPOT_LON = 8.5987;
    private static final double KARLSRUHE_LAT = 49.0069, KARLSRUHE_LON = 8.4037;
    /** Bruchsal ↔ Karlsruhe 单程 19.2936 km，往返 38.5872 km（独立用 Haversine 算出）。 */
    private static final double ROUND_TRIP_KM = 38.6;

    @Test
    @DisplayName("演示问题：能解出路线，总里程是真实公里数（而不是「度」）")
    void solvesDemoProblemWithRealKilometers() {
        VrpProblemFactory.Spec spec = FACTORY.demo();

        VrpPlan plan = service.solve(spec.problem(), spec.maxIterations());

        assertTrue(plan.feasible(), "演示问题必然有解：" + plan.message());
        assertEquals(1, plan.routeCount(), "总需求 14 ≤ 单车载重 20，最优解是 1 辆车跑完");
        assertEquals(4, plan.stopCount());
        assertTrue(plan.unassignedJobIds().isEmpty());
        // 旧实现（jsprit 默认欧氏距离）会把里程算成 3.6「度」；这里必须是四级数的公里数
        assertTrue(plan.totalDistanceKm() > 550 && plan.totalDistanceKm() < 700,
                "总里程应在 550~700 km（Bruchsal 出发跑完 4 城并返仓），实际: " + plan.totalDistanceKm());
        assertEquals(plan.routes().stream().mapToDouble(VrpPlan.Route::distanceKm).sum(),
                plan.totalDistanceKm(), 0.2, "总里程应等于各路线里程之和");
    }

    @Test
    @DisplayName("单作业闭环：Bruchsal → Karlsruhe → Bruchsal = 38.6 km（几何锁定，与搜索过程无关）")
    void singleJobRouteIsRoundTripInKilometers() {
        VrpProblemFactory.Spec spec = FACTORY.fromRequest(new OptimizeRequest(
                new OptimizeRequest.Point("Bruchsal-总仓", DEPOT_LAT, DEPOT_LON),
                List.of(new OptimizeRequest.VehicleSpec("V-01", 10.0)),
                List.of(new OptimizeRequest.DeliverySpec("D-1", "Karlsruhe", KARLSRUHE_LAT, KARLSRUHE_LON, 2.0)),
                null,
                null));

        VrpPlan plan = service.solve(spec.problem(), spec.maxIterations());

        assertEquals(38.6, plan.totalDistanceKm(), 0.05);
        VrpPlan.Route route = plan.routes().get(0);
        assertEquals("V-01", route.vehicleId());
        assertEquals("Bruchsal-总仓", route.depotId(), "车位起点应为仓库，且带可读 id 而不是 [x=..][y=..]");
        assertEquals(1, route.stops().size());
        assertEquals(2.0, route.load(), 1e-9);

        VrpPlan.Stop stop = route.stops().get(0);
        assertEquals(1, stop.sequence());
        assertEquals("D-1", stop.jobId());
        assertEquals("Karlsruhe", stop.name());
        assertEquals(KARLSRUHE_LAT, stop.lat(), 1e-9);
        assertEquals(KARLSRUHE_LON, stop.lon(), 1e-9);
        assertEquals(2.0, stop.demand(), 1e-9);
        // 单程 19.2936 km / 60 km/h ≈ 0.32 h
        assertEquals(0.32, stop.arrivalHours(), 0.01);
    }

    @Test
    @DisplayName("运力不足：返回「无可行路线」哨兵 + 列出全部未指派作业，而不是抛异常或 500")
    void infeasibleProblemReportsUnassignedJobs() {
        VrpProblemFactory.Spec spec = FACTORY.fromRequest(new OptimizeRequest(
                new OptimizeRequest.Point("Bruchsal-总仓", DEPOT_LAT, DEPOT_LON),
                List.of(new OptimizeRequest.VehicleSpec("V-01", 1.0)),
                List.of(new OptimizeRequest.DeliverySpec("D-BIG", "Karlsruhe", KARLSRUHE_LAT, KARLSRUHE_LON, 50.0)),
                null,
                null));

        VrpPlan plan = service.solve(spec.problem(), spec.maxIterations());

        assertFalse(plan.feasible());
        assertEquals(VrpPlan.NO_FEASIBLE_ROUTE_MESSAGE, plan.message());
        assertTrue(plan.routes().isEmpty());
        assertEquals(0, plan.routeCount());
        assertEquals(0.0, plan.totalDistanceKm(), 1e-9);
        assertEquals(List.of("D-BIG"), plan.unassignedJobIds());
    }

    @Test
    @DisplayName("空问题：仍然返回「无可行路线」（jsprit 给的是 0 条路线的非 null 解，判定必须看路线数）")
    void emptyProblemIsInfeasible() {
        VehicleRoutingProblem empty = VehicleRoutingProblem.Builder.newInstance()
                .setRoutingCost(new HaversineCostModel())
                .build();

        VrpPlan plan = service.solve(empty, VrpProblemFactory.DEFAULT_MAX_ITERATIONS);

        assertFalse(plan.feasible());
        assertEquals(VrpPlan.NO_FEASIBLE_ROUTE_MESSAGE, plan.message());
        assertTrue(plan.routes().isEmpty());
        assertTrue(plan.unassignedJobIds().isEmpty());
    }

    @Test
    @DisplayName("可复现：同一问题连续两次求解，总里程与停靠顺序完全一致（固定随机种子）")
    void solvingIsDeterministic() {
        VrpProblemFactory.Spec first = FACTORY.demo();
        VrpProblemFactory.Spec second = FACTORY.demo();

        VrpPlan plan1 = service.solve(first.problem(), first.maxIterations());
        VrpPlan plan2 = service.solve(second.problem(), second.maxIterations());

        assertEquals(plan1.totalDistanceKm(), plan2.totalDistanceKm(), 1e-9);
        assertEquals(signatureOf(plan1), signatureOf(plan2));
    }

    @Test
    @DisplayName("容量约束被遵守，且同一条路线内到达时刻单调递增")
    void respectsCapacityAndOrdersArrivalTimes() {
        VrpProblemFactory.Spec spec = FACTORY.demo();

        VrpPlan plan = service.solve(spec.problem(), spec.maxIterations());

        for (VrpPlan.Route route : plan.routes()) {
            assertTrue(route.load() <= 20.0, "路线载重不得超过车辆容量 20，实际: " + route.load());
            double previousArrival = -1.0;
            for (VrpPlan.Stop stop : route.stops()) {
                assertTrue(stop.arrivalHours() >= previousArrival,
                        "到达时刻应单调递增：" + route.stops());
                previousArrival = stop.arrivalHours();
                assertTrue(stop.demand() > 0, "停靠点需求应为正数");
            }
        }
    }

    @Test
    @DisplayName("默认重载 solve(problem) 等价于 solve(problem, DEFAULT_MAX_ITERATIONS)")
    void defaultOverloadUsesDefaultIterations() {
        VrpProblemFactory.Spec spec = FACTORY.demo();

        VrpPlan withDefault = service.solve(spec.problem());
        VrpPlan withExplicit = service.solve(spec.problem(), VrpProblemFactory.DEFAULT_MAX_ITERATIONS);

        assertEquals(withExplicit.totalDistanceKm(), withDefault.totalDistanceKm(), 1e-9);
    }

    @Test
    @DisplayName("回归证据：旧的「裸 SearchStrategyManager」写法必然抛 no search-strategy found（缺陷 #27 根因）")
    void bareSearchStrategyManagerStillFails() {
        VehicleRoutingProblem problem = twoJobProblem();
        VehicleRoutingAlgorithm algorithm = new VehicleRoutingAlgorithm(problem, new SearchStrategyManager());

        IllegalStateException ex = assertThrows(IllegalStateException.class, algorithm::searchSolutions);

        assertTrue(ex.getMessage().contains("no search-strategy found"),
                "jsprit 的 SearchStrategyManager 只是策略容器，未注册策略就必抛此异常；实际: " + ex.getMessage());
    }

    // ---------------- P1-1：可观测性（指标真实递增） ----------------

    @Test
    @DisplayName("P1-1：求解被计时（feasible=true）并记录总里程分布")
    void solveMetricsRecordDurationAndDistance() {
        VrpProblemFactory.Spec spec = FACTORY.demo();

        VrpPlan plan = service.solve(spec.problem(), spec.maxIterations());

        assertEquals(1, meterRegistry.get(VrpRouteService.OBSERVATION_SOLVE)
                .tags("feasible", "true").timer().count());
        DistributionSummary distance = meterRegistry.get(VrpRouteService.METRIC_DISTANCE).summary();
        assertEquals(1, distance.count());
        assertEquals(plan.totalDistanceKm(), distance.totalAmount(), 1e-9);
    }

    @Test
    @DisplayName("P1-1：无解只记 feasible=false 的耗时，不产生里程样本（否则均值会被 0 拉低）")
    void infeasibleSolveIsCountedButNotAsDistance() {
        VrpProblemFactory.Spec spec = FACTORY.fromRequest(new OptimizeRequest(
                new OptimizeRequest.Point("Bruchsal-总仓", DEPOT_LAT, DEPOT_LON),
                List.of(new OptimizeRequest.VehicleSpec("V-01", 1.0)),
                List.of(new OptimizeRequest.DeliverySpec("D-BIG", "Karlsruhe", KARLSRUHE_LAT, KARLSRUHE_LON, 50.0)),
                null,
                null));

        VrpPlan plan = service.solve(spec.problem(), spec.maxIterations());

        assertFalse(plan.feasible());
        assertEquals(1, meterRegistry.get(VrpRouteService.OBSERVATION_SOLVE)
                .tags("feasible", "false").timer().count(),
                "无解次数突增往往意味着需求/运力数据有问题，必须能监控到");
        assertNull(meterRegistry.find(VrpRouteService.METRIC_DISTANCE).summary());
    }

    @Test
    @DisplayName("P1-3：求解产出 span，且高低基数标签分层正确（低基数进指标、高基数只进 span）")
    void solveProducesSpanWithLayeredKeyValues() {
        List<Observation.Context> contexts = new ArrayList<>();
        ObservationRegistry capturing = ObservationRegistry.create();
        capturing.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public void onStart(Observation.Context context) {
                contexts.add(context);
            }

            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        VrpRouteService traced = new VrpRouteService(new SimpleMeterRegistry(), capturing);
        VrpProblemFactory.Spec spec = FACTORY.demo();

        traced.solve(spec.problem(), spec.maxIterations());

        assertEquals(1, contexts.size(), "一次求解应恰好产出一个 span");
        Observation.Context context = contexts.get(0);
        assertEquals(VrpRouteService.OBSERVATION_SOLVE, context.getName());
        assertEquals("true", context.getLowCardinalityKeyValue("feasible").getValue());
        assertEquals("4", context.getHighCardinalityKeyValue("stopCount").getValue());
        assertNotNull(context.getHighCardinalityKeyValue("totalDistanceKm"));
        assertNull(context.getLowCardinalityKeyValue("stopCount"),
                "高基数键混进低基就会跟着指标走，把时间序列炸掉");
    }

    /** 模拟 Boot 的自动配置：注册指标观察处理器（否则 Observation 只出 span 不出指标）。 */
    private static ObservationRegistry observationRegistry(SimpleMeterRegistry meterRegistry) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        return registry;
    }

    @Test
    @DisplayName("未指派作业按 id 排序输出（回执稳定，便于端到端断言）")
    void unassignedJobsAreSorted() {
        VrpProblemFactory.Spec spec = FACTORY.fromRequest(new OptimizeRequest(
                new OptimizeRequest.Point("Bruchsal-总仓", DEPOT_LAT, DEPOT_LON),
                List.of(new OptimizeRequest.VehicleSpec("V-01", 1.0)),
                List.of(new OptimizeRequest.DeliverySpec("Z-LAST", "Karlsruhe", KARLSRUHE_LAT, KARLSRUHE_LON, 9.0),
                        new OptimizeRequest.DeliverySpec("A-FIRST", "Bruchsal", 49.11, 8.60, 9.0)),
                null,
                null));

        VrpPlan plan = service.solve(spec.problem(), spec.maxIterations());

        assertFalse(plan.feasible());
        assertEquals(List.of("A-FIRST", "Z-LAST"), plan.unassignedJobIds());
        // 未指派时也要给出可读文案，而不是 jsprit 的 [costs=0.0][routes=0][unassigned=0]
        assertEquals(VrpPlan.NO_FEASIBLE_ROUTE_MESSAGE, plan.message());
    }

    /** 路线签名（车辆 + 停靠顺序），用于比较两次求解是否完全一致。 */
    private static List<String> signatureOf(VrpPlan plan) {
        return plan.routes().stream()
                .map(route -> route.vehicleId() + ":" + route.stops().stream()
                        .map(VrpPlan.Stop::jobId)
                        .collect(Collectors.joining("->")))
                .toList();
    }

    /** 两个作业 + 一辆车（供“旧写法必然失败”的回归用例使用）。 */
    private static VehicleRoutingProblem twoJobProblem() {
        Location depot = Location.Builder.newInstance().setId("depot")
                .setCoordinate(Coordinate.newInstance(DEPOT_LAT, DEPOT_LON)).build();
        VehicleImpl vehicle = VehicleImpl.Builder.newInstance("V-01")
                .setStartLocation(depot)
                .setType(VehicleTypeImpl.Builder.newInstance("t").addCapacityDimension(0, 10).build())
                .build();
        Service job = Service.Builder.newInstance("D-1")
                .setLocation(Location.Builder.newInstance().setId("D-1")
                        .setCoordinate(Coordinate.newInstance(KARLSRUHE_LAT, KARLSRUHE_LON)).build())
                .addSizeDimension(0, 1)
                .build();
        return VehicleRoutingProblem.Builder.newInstance()
                .setRoutingCost(new HaversineCostModel())
                .addVehicle(vehicle)
                .addJob(job)
                .build();
    }
}
