package com.aslp.route.controller;

import com.aslp.route.dto.VrpPlan;
import com.aslp.route.engine.VrpProblemFactory;
import com.aslp.route.service.VrpRouteService;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RouteController 切片测试（M3 路径优化）。
 *
 * <p>历史：原 {@code RouteControllerTest} 因缺少 {@code spring-boot-test} 依赖无法编译，
 * 补齐依赖后改为 {@code @WebMvcTest} 切片测试；P1-2b 起 {@code /optimize} 接入真实引擎。
 *
 * <p>切片策略：{@code VrpRouteService}（求解器）为 mock，避免切片里真跑 jsprit；
 * {@link VrpProblemFactory} 则用真实实现（{@code @Import}）—— 只有让真实工厂参与，
 * 才能验证「JSON → jsprit 问题」的映射与 400 校验这两条链路。
 */
@WebMvcTest(RouteController.class)
@Import(VrpProblemFactory.class)
class RouteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VrpRouteService vrpRouteService;

    @Test
    @DisplayName("健康检查返回引擎信息（网关路由断言依赖 status 字段）")
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/routes/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("route-service up"))
                .andExpect(jsonPath("$.engine").value("jsprit VRP"));
    }

    @Test
    @DisplayName("历史上/单数路径前缀仍然可用（网关断言用的是复数前缀）")
    void legacySingularPathStillWorks() throws Exception {
        mockMvc.perform(get("/api/route/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("不传 body：用内置演示问题求解（保持 curl -X POST .../optimize 的老用法可用）")
    void optimizeWithoutBodyUsesDemoProblem() throws Exception {
        given(vrpRouteService.solve(any(VehicleRoutingProblem.class), anyInt()))
                .willReturn(samplePlan());

        mockMvc.perform(post("/api/routes/optimize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.routeCount").value(1))
                .andExpect(jsonPath("$.message").value("已生成 1 条配送路线，总里程 615.1 km"));

        ArgumentCaptor<VehicleRoutingProblem> captor = ArgumentCaptor.forClass(VehicleRoutingProblem.class);
        verify(vrpRouteService).solve(captor.capture(), anyInt());
        assertEquals(4, captor.getValue().getJobs().size(), "演示问题应有 4 个配送作业");
        assertEquals(2, captor.getValue().getVehicles().size());
    }

    @Test
    @DisplayName("传 body：JSON 被真实映射为 jsprit 问题（作业 id / 需求 / 迭代数全部落到引擎入参）")
    void optimizeWithBodyMapsRequestToProblem() throws Exception {
        given(vrpRouteService.solve(any(VehicleRoutingProblem.class), anyInt()))
                .willReturn(samplePlan());

        String body = """
                {
                  "depot":   { "id": "Bruchsal-总仓", "lat": 49.1243, "lon": 8.5987 },
                  "vehicles":[ { "id": "V-01", "capacity": 30 } ],
                  "deliveries":[ { "id": "D-1", "name": "Karlsruhe", "lat": 49.0069, "lon": 8.4037, "demand": 2 },
                                 { "id": "D-2", "name": "Frankfurt", "lat": 50.1109, "lon": 8.6821, "demand": 3 } ],
                  "speedKmh": 90,
                  "maxIterations": 500
                }
                """;

        mockMvc.perform(post("/api/routes/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true));

        ArgumentCaptor<VehicleRoutingProblem> captor = ArgumentCaptor.forClass(VehicleRoutingProblem.class);
        verify(vrpRouteService).solve(captor.capture(), eq(500));
        VehicleRoutingProblem problem = captor.getValue();
        assertEquals(2, problem.getJobs().size());
        assertEquals("Karlsruhe", problem.getJobs().get("D-1").getName());
        assertEquals(1, problem.getVehicles().size());
        assertEquals(30, problem.getVehicles().iterator().next()
                .getType().getCapacityDimensions().get(0));
    }

    @Test
    @DisplayName("入参非法（缺 deliveries）→ 400 + 中文原因，且不触达求解器")
    void optimizeWithInvalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/routes/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "depot": { "id": "B", "lat": 49.1243, "lon": 8.5987 },
                                  "vehicles": [ { "id": "V-01", "capacity": 10 } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("deliveries")));

        verify(vrpRouteService, never()).solve(any(VehicleRoutingProblem.class), anyInt());
    }

    @Test
    @DisplayName("坐标缺失 → 400（包装类型让“没传”与“传了 0”可区分，不会静默按几内亚湾算）")
    void optimizeWithMissingCoordinateReturns400() throws Exception {
        mockMvc.perform(post("/api/routes/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "depot": { "id": "B", "lat": 49.1243, "lon": 8.5987 },
                                  "vehicles": [ { "id": "V-01", "capacity": 10 } ],
                                  "deliveries": [ { "id": "D-1", "lat": 49.0069, "demand": 1 } ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("deliveries[0] 需要同时提供 lat 与 lon")));
    }

    /** 供 mock 返回的最小计划。 */
    private static VrpPlan samplePlan() {
        return new VrpPlan(true, "已生成 1 条配送路线，总里程 615.1 km", 615.1, 14.0, 1, 4,
                List.of(), 42L,
                List.of(new VrpPlan.Route("VRP-01", "V-02", "Bruchsal-总仓", 615.1, 14.0,
                        List.of(new VrpPlan.Stop(1, "VRP-D-02", "Frankfurt", 50.1109, 8.6821, 5.0, 1.83)))));
    }
}
