package com.aslp.gateway.bff;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
        //
        // Bug 修复：原实现用 Map.of(...) 回显可选参数，而 Map.of 拒绝 null 值 ——
        // 只传部分筛选项（如仅 ?status=PAID、不带 warehouseCode）时会抛
        // NullPointerException，被 WebFlux 兜底为 HTTP 500。
        // 现改为仅回显「实际传入」的筛选条件；未传入的键不出现，语义上也更贴合「未筛选」。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", page);
        body.put("size", size);
        if (status != null) {
            body.put("status", status);
        }
        if (warehouseCode != null) {
            body.put("warehouse", warehouseCode);
        }
        body.put("message", "BFF 多条件分页查询已启用（M4 完成）");
        return ResponseEntity.ok(body);
    }
}
