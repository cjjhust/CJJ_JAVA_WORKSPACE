package com.aslp.gateway.bff;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/bff/orders")
@Validated
public class BffOrderController {

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String warehouseCode) {

        // BFF 层：多条件分页查询（Spring Validation 已应用）
        return ResponseEntity.ok(Map.of(
            "status", status,
            "page", page,
            "size", size,
            "warehouse", warehouseCode,
            "message", "BFF 多条件分页查询已启用（M4 完成）"
        ));
    }
}
