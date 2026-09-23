package com.aslp.report.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表服务集成测试（M5）。
 *
 * <p><b>为什么要真的启动 Spring 上下文，而不是只 new 一个 controller</b>：
 * 本项目已经有过一次教训（readme §9 #43）—— 新建的 {@code @ConfigurationProperties}
 * 忘了写进 {@code @EnableConfigurationProperties}，编译无错、单测全绿，
 * 只有真的启动上下文才会炸。所以「能起来」这件事本身必须被测试覆盖。
 *
 * <p>两个下游地址被指向 {@code 127.0.0.1:1}（不会有服务监听），
 * 于是本测试顺带钉死了另一条关键语义：<b>上游不可用时看板必须降级，而不是 500</b>。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "aslp.report.order-base-url=http://127.0.0.1:1",
                "aslp.report.inventory-base-url=http://127.0.0.1:1"
        })
class ReportControllerTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/reports/health", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("report-service up", response.getBody().get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardDegradesGracefullyWhenDownstreamsAreUnreachable() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/reports/dashboard", Map.class);

        // 200 而不是 500：依赖故障不该让「看板」整页打不开
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("degraded"));
        assertEquals(List.of("order-service", "inventory-service"), body.get("unavailable"));

        // 分块结构必须存在且如实标注不可用，而不是返回一个「看起来正常的全 0 看板」
        Map<String, Object> orders = (Map<String, Object>) body.get("orders");
        Map<String, Object> inventory = (Map<String, Object>) body.get("inventory");
        assertEquals(Boolean.FALSE, orders.get("available"));
        assertEquals(Boolean.FALSE, inventory.get("available"));
    }

    @Test
    void staticDashboardPageIsServed() {
        // 看板页面由本服务同源托管（免 CORS 配置）；这里断言资源确实被打进了 jar
        ResponseEntity<String> response = restTemplate.getForEntity("/dashboard.html", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("ASLP 运营看板"));
    }
}
