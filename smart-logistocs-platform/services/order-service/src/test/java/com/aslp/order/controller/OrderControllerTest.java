package com.aslp.order.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class OrderControllerTest {

    @Test
    public void healthReturnsExpectedMessage() {
        OrderController controller = new OrderController();
        String result = controller.health();
        assertEquals("Order Service OK - Bruchsal / Mönchengladbach", result);
    }
}
