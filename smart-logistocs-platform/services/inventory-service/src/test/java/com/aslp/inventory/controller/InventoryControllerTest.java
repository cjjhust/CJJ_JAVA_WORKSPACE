package com.aslp.inventory.controller;

import com.aslp.inventory.repository.InventoryRepository;
import com.aslp.inventory.service.InventoryLockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InventoryController 切片测试。
 *
 * <p>历史 Bug：原测试使用 {@code @WebMvcTest} 但未提供 {@code InventoryRepository} /
 * {@code InventoryLockService} 依赖，上下文启动失败。现补 {@code @MockBean}。
 * 同时移除了不存在的 {@code test} profile 依赖（无 application-test.yml）。
 */
@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryRepository repository;

    @MockBean
    private InventoryLockService lockService;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/inventory/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inventory-service up"))
                .andExpect(jsonPath("$.warehouse").value("Bruchsal / Mönchengladbach"));
    }
}
