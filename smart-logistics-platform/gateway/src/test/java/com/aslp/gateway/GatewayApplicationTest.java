package com.aslp.gateway;

import com.aslp.gateway.bff.BffOrderController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayApplicationTest {

    @Test
    void applicationClassIsPresent() {
        assertNotNull(GatewayApplication.class);
    }

    @Test
    void bffSearchEchoesQueryParameters() {
        BffOrderController controller = new BffOrderController();
        ResponseEntity<Map<String, Object>> response =
                controller.search("SHIPPED", 2, 20, "Bruchsal");

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("SHIPPED", body.get("status"));
        assertEquals(2, body.get("page"));
        assertEquals(20, body.get("size"));
        assertEquals("Bruchsal", body.get("warehouse"));
        assertTrue(String.valueOf(body.get("message")).contains("BFF"));
    }

    /**
     * 回归测试：可选筛选项缺省时不得抛 NPE。
     *
     * <p>历史 Bug：原实现用 {@code Map.of(...)} 回显参数，而 Map.of 拒绝 null 值，
     * 导致 {@code GET /bff/orders/search?status=PAID}（不带 warehouseCode）返回 500。
     */
    @Test
    void bffSearchToleratesOmittedOptionalParameters() {
        BffOrderController controller = new BffOrderController();
        ResponseEntity<Map<String, Object>> response =
                controller.search("PAID", 1, 10, null);

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("PAID", body.get("status"));
        assertFalse(body.containsKey("warehouse"), "未传入的筛选项不应出现在响应中");
        assertTrue(String.valueOf(body.get("message")).contains("BFF"));
    }
}
