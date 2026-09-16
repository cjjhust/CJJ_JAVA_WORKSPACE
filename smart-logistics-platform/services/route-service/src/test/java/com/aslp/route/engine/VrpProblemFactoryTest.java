package com.aslp.route.engine;

import com.aslp.route.dto.OptimizeRequest;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VrpProblemFactory} 单元测试：入参校验 + jsprit 问题装配。
 *
 * <p>校验用例刻意覆盖「静默出错」的几种情形 —— 缺字段（包装类型为 null）、
 * 小数容量（jsprit 维度是 int 会被截断）、id 重复（未指派回执无法定位作业）。
 * 这些都必须变成 400 而不是被默默接受。
 */
class VrpProblemFactoryTest {

    private final VrpProblemFactory factory = new VrpProblemFactory();

    @Test
    @DisplayName("内置演示问题：Bruchsal 总仓 + 2 辆载重 20 的车 + 4 个德国收货点")
    void demoProblemHasExpectedShape() {
        VrpProblemFactory.Spec spec = factory.demo();

        assertEquals(4, spec.problem().getJobs().size());
        assertEquals(2, spec.problem().getVehicles().size());
        assertEquals(VrpProblemFactory.DEFAULT_MAX_ITERATIONS, spec.maxIterations());
        assertEquals(VrpProblemFactory.DEFAULT_SPEED_KMH, spec.speedKmh(), 1e-9);
        assertTrue(spec.problem().getJobs().containsKey("VRP-D-01"));
        assertTrue(spec.problem().getJobs().containsKey("VRP-D-04"));
    }

    @Test
    @DisplayName("显式请求：作业/车辆/仓库按 id 装配，自定义车速与迭代数透传")
    void buildsProblemFromExplicitRequest() {
        VrpProblemFactory.Spec spec = factory.fromRequest(new OptimizeRequest(
                new OptimizeRequest.Point("Bruchsal-总仓", 49.1243, 8.5987),
                List.of(new OptimizeRequest.VehicleSpec("V-01", 30.0)),
                List.of(new OptimizeRequest.DeliverySpec("D-1", "Karlsruhe", 49.0069, 8.4037, 2.0)),
                90.0,
                500));

        VehicleRoutingProblem problem = spec.problem();
        assertEquals(1, problem.getJobs().size());
        assertEquals(1, problem.getVehicles().size());
        assertEquals(90.0, spec.speedKmh(), 1e-9);
        assertEquals(500, spec.maxIterations());
        assertEquals("Karlsruhe", problem.getJobs().get("D-1").getName());
        assertEquals(2, problem.getJobs().get("D-1").getSize().get(0));
        assertEquals(30, vehicleById(problem, "V-01").getType().getCapacityDimensions().get(0));
    }

    @Test
    @DisplayName("车辆 id 留空自动补 V-01/V-02；作业名留空回落为作业 id")
    void fillsMissingIdsAndNames() {
        VrpProblemFactory.Spec spec = factory.fromRequest(new OptimizeRequest(
                new OptimizeRequest.Point(null, 49.1243, 8.5987),
                List.of(new OptimizeRequest.VehicleSpec("  ", 10.0),
                        new OptimizeRequest.VehicleSpec(null, 10.0)),
                List.of(new OptimizeRequest.DeliverySpec("D-1", null, 49.0069, 8.4037, 1.0)),
                null,
                null));

        assertTrue(vehicleIds(spec.problem()).contains("V-01"));
        assertTrue(vehicleIds(spec.problem()).contains("V-02"));
        assertEquals("D-1", spec.problem().getJobs().get("D-1").getName());
        assertEquals("DEPOT", spec.problem().getVehicles().iterator().next()
                .getStartLocation().getId());
    }

    @Test
    @DisplayName("请求体为 null（未传 body）等价于演示问题")
    void nullRequestFallsBackToDemo() {
        VrpProblemFactory.Spec spec = factory.fromRequest(null);

        assertEquals(4, spec.problem().getJobs().size());
    }

    @Test
    @DisplayName("缺仓库 / 坐标不成对 / 坐标越界 → 校验失败并指出字段")
    void rejectsInvalidDepot() {
        assertMessageContains("depot",
                () -> factory.fromRequest(request(null, 10.0, oneDelivery(D("D-1", 49.0, 8.4, 1.0)))));

        assertMessageContains("depot 需要同时提供 lat 与 lon",
                () -> factory.fromRequest(request(D("B", 49.1243, null), 10.0, oneDelivery(D("D-1", 49.0, 8.4, 1.0)))));

        assertMessageContains("depot.lat 必须在 -90 ~ 90",
                () -> factory.fromRequest(request(D("B", 199.0, 8.5987), 10.0, oneDelivery(D("D-1", 49.0, 8.4, 1.0)))));

        assertMessageContains("deliveries[0].lon 必须在 -180 ~ 180",
                () -> factory.fromRequest(request(D("B", 49.1243, 8.5987), 10.0, oneDelivery(D("D-1", 49.0, 999.0, 1.0)))));
    }

    @Test
    @DisplayName("容量必须是正整数：null / 0 / 负数 / 小数都被拒绝（jsprit 的维度是 int，小数会被静默截断）")
    void rejectsInvalidCapacity() {
        assertMessageContains("vehicles[0].capacity 必填",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987),
                        List.of(new OptimizeRequest.VehicleSpec("V-01", null)),
                        oneDelivery(D("D-1", 49.0, 8.4, 1.0)), null, null)));

        assertMessageContains("vehicles[0].capacity 必须是正整数",
                () -> factory.fromRequest(request(D("B", 49.1243, 8.5987), 0.0, oneDelivery(D("D-1", 49.0, 8.4, 1.0)))));

        assertMessageContains("vehicles[0].capacity 必须是正整数",
                () -> factory.fromRequest(request(D("B", 49.1243, 8.5987), -5.0, oneDelivery(D("D-1", 49.0, 8.4, 1.0)))));

        assertMessageContains("vehicles[0].capacity 必须是正整数（jsprit 1.8 的容量维度为 int）：2.5",
                () -> factory.fromRequest(request(D("B", 49.1243, 8.5987), 2.5, oneDelivery(D("D-1", 49.0, 8.4, 1.0)))));
    }

    @Test
    @DisplayName("需求量为 null / 0 → 拒绝；作业 id 缺失 → 拒绝（未指派回执需要它定位作业）")
    void rejectsInvalidDeliveries() {
        assertMessageContains("deliveries[0].demand 必填",
                () -> factory.fromRequest(request(D("B", 49.1243, 8.5987), 10.0,
                        oneDelivery(D("D-1", 49.0, 8.4, null)))));

        assertMessageContains("deliveries[0].demand 必须是正整数",
                () -> factory.fromRequest(request(D("B", 49.1243, 8.5987), 10.0,
                        oneDelivery(D("D-1", 49.0, 8.4, 0.0)))));

        assertMessageContains("deliveries[0].id 必填",
                () -> factory.fromRequest(request(D("B", 49.1243, 8.5987), 10.0,
                        oneDelivery(D(null, 49.0, 8.4, 1.0)))));
    }

    @Test
    @DisplayName("车辆/作业 id 重复 → 拒绝（否则 jsprit 会覆盖，静默丢掉一票）")
    void rejectsDuplicateIds() {
        assertMessageContains("车辆 id 重复：V-01",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987),
                        List.of(new OptimizeRequest.VehicleSpec("V-01", 10.0),
                                new OptimizeRequest.VehicleSpec("V-01", 10.0)),
                        oneDelivery(D("D-1", 49.0, 8.4, 1.0)), null, null)));

        assertMessageContains("作业 id 重复：D-1",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987),
                        List.of(new OptimizeRequest.VehicleSpec("V-01", 10.0)),
                        List.of(D("D-1", 49.0, 8.4, 1.0), D("D-1", 49.1, 8.5, 1.0)), null, null)));
    }

    @Test
    @DisplayName("车辆集合或作业集合为空 → 拒绝")
    void rejectsEmptyCollections() {
        assertMessageContains("vehicles 至少需要 1 辆车",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987), List.of(),
                        oneDelivery(D("D-1", 49.0, 8.4, 1.0)), null, null)));

        assertMessageContains("deliveries 至少需要 1 个配送作业",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987),
                        List.of(new OptimizeRequest.VehicleSpec("V-01", 10.0)), List.of(), null, null)));
    }

    @Test
    @DisplayName("求解参数越界 → 拒绝并给出区间（不做静默夹紧，避免“以为调了 5000 实际跑 2000”）")
    void rejectsOutOfRangeSolverParameters() {
        assertMessageContains("maxIterations 必须在 100 ~ 5000",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987),
                        List.of(new OptimizeRequest.VehicleSpec("V-01", 10.0)),
                        oneDelivery(D("D-1", 49.0, 8.4, 1.0)), null, 50)));

        assertMessageContains("maxIterations 必须在 100 ~ 5000",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987),
                        List.of(new OptimizeRequest.VehicleSpec("V-01", 10.0)),
                        oneDelivery(D("D-1", 49.0, 8.4, 1.0)), null, 99999)));

        assertMessageContains("speedKmh 必须在 0 ~ 130",
                () -> factory.fromRequest(new OptimizeRequest(D("B", 49.1243, 8.5987),
                        List.of(new OptimizeRequest.VehicleSpec("V-01", 10.0)),
                        oneDelivery(D("D-1", 49.0, 8.4, 1.0)), 300.0, null)));
    }

    @Test
    @DisplayName("多个字段同时出错时，一次性列出全部问题（避免修一个报一个）")
    void reportsAllErrorsAtOnce() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.fromRequest(new OptimizeRequest(null, List.of(),
                        List.of(D("D-1", 999.0, 8.4, 1.0)), null, null)));

        assertTrue(ex.getMessage().contains("depot 必填"), ex.getMessage());
        assertTrue(ex.getMessage().contains("vehicles 至少需要 1 辆车"), ex.getMessage());
        assertTrue(ex.getMessage().contains("deliveries[0].lat 必须在 -90 ~ 90"), ex.getMessage());
    }

    // ---------- 构造辅助 ----------

    private static OptimizeRequest request(OptimizeRequest.Point depot, Double capacity,
                                          List<OptimizeRequest.DeliverySpec> deliveries) {
        return new OptimizeRequest(depot, List.of(new OptimizeRequest.VehicleSpec("V-01", capacity)),
                deliveries, null, null);
    }

    private static List<OptimizeRequest.DeliverySpec> oneDelivery(OptimizeRequest.DeliverySpec spec) {
        return List.of(spec);
    }

    /** 仓库/作业点（纬度, 经度）。 */
    private static OptimizeRequest.Point D(String id, Double lat, Double lon) {
        return new OptimizeRequest.Point(id, lat, lon);
    }

    private static OptimizeRequest.DeliverySpec D(String id, Double lat, Double lon, Double demand) {
        return new OptimizeRequest.DeliverySpec(id, id, lat, lon, demand);
    }

    private static void assertMessageContains(String expected, Executable action) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, action);
        assertTrue(ex.getMessage().contains(expected),
                "期望消息包含「" + expected + "」，实际：" + ex.getMessage());
    }

    /** {@code getVehicles()} 是 Collection 而非 Map，这里按 id 取值。 */
    private static Vehicle vehicleById(VehicleRoutingProblem problem, String id) {
        return problem.getVehicles().stream()
                .filter(vehicle -> id.equals(vehicle.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("车辆不存在：" + id));
    }

    private static java.util.List<String> vehicleIds(VehicleRoutingProblem problem) {
        return problem.getVehicles().stream().map(Vehicle::getId).toList();
    }
}
