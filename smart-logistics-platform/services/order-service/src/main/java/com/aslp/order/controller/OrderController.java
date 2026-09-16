package com.aslp.order.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    @GetMapping("/api/orders/health")
    public String health() {
        return "Order Service OK - Bruchsal / Mönchengladbach";
    }
}
