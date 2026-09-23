package com.aslp.order.controller;

import com.aslp.order.entity.OrderRecord;
import com.aslp.order.repository.OrderRepository;
import com.aslp.order.service.OrderPullService;
import com.aslp.order.strategy.PlatformFailureSwitch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单统一接入 REST 接口（M1）。
 *
 * <p>对应 M1 任务拆解：
 * <ul>
 *   <li>「流水线式的自动导入」 → {@code POST /api/orders/pull}</li>
 *   <li>「异常物流单号手动修正后台」 → {@code POST /api/orders/{orderId}/correct}</li>
 *   <li>「多条件查询」 → {@code GET /api/orders}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderPullController {

    private final OrderPullService pullService;
    private final OrderRepository repository;
    private final PlatformFailureSwitch failureSwitch;

    public OrderPullController(OrderPullService pullService, OrderRepository repository,
                               PlatformFailureSwitch failureSwitch) {
        this.pullService = pullService;
        this.repository = repository;
        this.failureSwitch = failureSwitch;
    }

    /** 触发一次订单拉取（策略模式 + 幂等落库 + P1-2 容错降级）。 */
    @PostMapping("/pull")
    public ResponseEntity<Map<String, Object>> pull() {
        OrderPullService.PullSummary summary = pullService.pullAndPersist();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", summary.platform());
        body.put("success", summary.success());
        body.put("fetched", summary.fetched());
        body.put("created", summary.created());
        body.put("updated", summary.updated());
        body.put("flagged", summary.flagged());
        body.put("degraded", summary.degraded());
        body.put("errorMsg", summary.errorMsg());
        return ResponseEntity.ok(body);
    }

    /**
     * P1-2 故障演练开关（仅 Mock 策略读取）。
     *
     * <p>用于验证「平台连续失败 -> 熔断打开 -> 接口返回降级响应（而非 500）」。
     * 真实策略无此开关；网关 docker 链路仅允许 ADMIN 调用。
     *
     * @param mode NONE（恢复正常）/ ERROR（注入平台故障）
     */
    @PostMapping("/mock/failure-mode")
    public ResponseEntity<Map<String, Object>> setMockFailureMode(@RequestParam String mode) {
        PlatformFailureSwitch.Mode target;
        try {
            target = PlatformFailureSwitch.Mode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        failureSwitch.setMode(target);
        return ResponseEntity.ok(Map.of(
                "mode", failureSwitch.getMode().name(),
                "failing", failureSwitch.isFailing()));
    }

    /** 多条件分页查询。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<OrderRecord> result;
        if (status != null && !status.isBlank() && warehouseCode != null && !warehouseCode.isBlank()) {
            result = repository.findByStatusAndWarehouseCode(status, warehouseCode, pageable);
        } else if (status != null && !status.isBlank()) {
            result = repository.findByStatus(status, pageable);
        } else if (warehouseCode != null && !warehouseCode.isBlank()) {
            result = repository.findByWarehouseCode(warehouseCode, pageable);
        } else {
            result = repository.findAll(pageable);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("items", result.getContent());
        return ResponseEntity.ok(body);
    }

    /** 汇总统计（给报表/BFF 看板用）。 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", repository.count());
        body.put("paid", repository.countByStatus("PAID"));
        body.put("shipped", repository.countByStatus("SHIPPED"));
        body.put("completed", repository.countByStatus("COMPLETED"));
        body.put("withErrorTag", repository.countByErrorTagNotNull());
        // M5 报表看板（report-service）需要「分布」而不是「单个数字」：
        // 状态分布画饼图、仓库分布画柱状图、异常标签分布回答「今天卡在哪一类问题上」。
        body.put("byStatus", groupToMap(repository.countGroupByStatus()));
        body.put("byWarehouse", groupToMap(repository.countGroupByWarehouseCode()));
        body.put("byErrorTag", groupToMap(repository.countGroupByErrorTag()));
        return ResponseEntity.ok(body);
    }

    /**
     * 把 [key, count] 形式的 group by 结果转成<b>有序</b> map。
     *
     * <p><b>为什么必须排序</b>：数据库的 {@code group by} <b>不保证行序</b>（Postgres 可能走 HashAggregate），
     * 所以"上游返回什么顺序就用什么顺序"等于把不确定性透传给下游 ——
     * 报表看板的图表颜色/顺序会随机跳变，端到端断言也会时通时断。
     * 这里按键排序，把"同一份数据两次请求顺序一致"变成一个可依赖的性质。
     *
     * <p>用 {@link LinkedHashMap} 承载排序结果（而不是直接返回 TreeMap）：
     * 下游只关心迭代顺序，返回 Map 更通用；键为 {@code null} 时统一记作 {@code UNKNOWN}，
     * 避免 JSON 里出现 null 键（部分前端图表库会直接报错）。
     */
    private static Map<String, Long> groupToMap(java.util.List<Object[]> rows) {
        Map<String, Long> sorted = new java.util.TreeMap<>();
        for (Object[] row : rows) {
            String key = row[0] == null ? "UNKNOWN" : String.valueOf(row[0]);
            Long count = row[1] == null ? 0L : ((Number) row[1]).longValue();
            sorted.put(key, count);
        }
        return new LinkedHashMap<>(sorted);
    }

    /** 单订单详情。 */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderRecord> detail(@PathVariable String orderId) {
        OrderRecord record = pullService.findByOrderId(orderId);
        return record == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(record);
    }

    /** 客服远程修正异常订单（清除异常标签）。 */
    @PostMapping("/{orderId}/correct")
    public ResponseEntity<Map<String, Object>> correct(
            @PathVariable String orderId,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String status) {

        boolean ok = pullService.correctOrder(orderId, warehouseCode, status);
        return ResponseEntity.ok(Map.of(
                "orderId", orderId,
                "corrected", ok,
                "message", ok ? "异常标签已清除，可重新进入履约流水线" : "订单不存在"
        ));
    }
}
