package com.aslp.route.controller;

import com.aslp.route.service.VrpRouteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/routes", "/api/route"})
public class RouteController {

    private final VrpRouteService vrpService;

    public RouteController(VrpRouteService vrpService) {
        this.vrpService = vrpService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "route-service up",
            "engine", "jsprit VRP",
            "region", "Europe (DHL/DPD)"
        ));
    }

    @PostMapping("/optimize")
    public ResponseEntity<Map<String, String>> optimize() {
        // 模拟 VRP 计算（实际业务中传入 VehicleRoutingProblem）
        return ResponseEntity.ok(Map.of(
            "message", "VRP 路径优化已触发（jsprit 引擎）",
            "region", "Bruchsal → Mönchengladbach"
        ));
    }
}
