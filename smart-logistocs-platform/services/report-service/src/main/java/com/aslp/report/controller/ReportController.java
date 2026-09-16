package com.aslp.report.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "report-service up",
            "feature", "ECharts 数据可视化看板",
            "milestone", "M1 完成"
        ));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard() {
        // 模拟 ECharts 数据源（订单量、库存、运费趋势）
        Map<String, Object> data = new HashMap<>();
        data.put("orders", List.of(120, 200, 150, 80, 70, 110, 130));
        data.put("inventory", List.of(500, 420, 380, 310, 290, 340, 410));
        data.put("routes", List.of(45, 52, 48, 60, 55, 58, 62));
        return ResponseEntity.ok(Map.of(
            "service", "report-service",
            "chart", "ECharts",
            "data", data
        ));
    }
}
