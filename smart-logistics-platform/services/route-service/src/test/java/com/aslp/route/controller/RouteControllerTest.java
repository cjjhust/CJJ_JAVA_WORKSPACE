package com.aslp.route.controller;

import com.aslp.route.service.VrpRouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RouteController 切片测试（M3 路径优化）。
 *
 * <p>说明：原 {@code RouteControllerTest} 因缺少 {@code spring-boot-starter-test} 依赖
 * 无法编译，已在修复依赖后恢复并改为 {@code @WebMvcTest} 切片测试。
 */
@WebMvcTest(RouteController.class)
class RouteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VrpRouteService vrpRouteService;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/routes/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("route-service up"))
                .andExpect(jsonPath("$.engine").value("jsprit VRP"));
    }

    @Test
    void legacySingularPathStillWorks() throws Exception {
        mockMvc.perform(get("/api/route/health"))
                .andExpect(status().isOk());
    }
}
