package com.aslp.gateway;

import com.aslp.gateway.bff.BffOrderController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
