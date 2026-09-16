package com.aslp.route.controller;

import com.aslp.route.dto.OptimizeRequest;
import com.aslp.route.dto.VrpPlan;
import com.aslp.route.engine.VrpProblemFactory;
import com.aslp.route.service.VrpRouteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3 路径优化 / 运费 / 健康检查入口。
 *
 * <p>兼容 {@code /api/routes/**} 与 {@code /api/route/**} 两种前缀
 * （网关断言用的是前者，见 readme §9 #14）。
 */
@RestController
@RequestMapping({"/api/routes", "/api/route"})
public class RouteController {

    private final VrpRouteService vrpService;
    private final VrpProblemFactory problemFactory;

    public RouteController(VrpRouteService vrpService, VrpProblemFactory problemFactory) {
        this.vrpService = vrpService;
        this.problemFactory = problemFactory;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "route-service up",
            "engine", "jsprit VRP",
            "algorithm", "ruin & recreate (Schrimpf) + haversine km",
            "region", "Europe (DHL/DPD)"
        ));
    }

    /**
     * 求解配送路径（P1-2b：从桩方法接入真实 jsprit 引擎）。
     *
     * <p>请求体<b>可省略</b>：不传时使用内置演示问题
     * （Bruchsal 总仓 → Karlsruhe / Frankfurt / Düsseldorf / Mönchengladbach），
     * 便于 {@code curl -X POST .../api/routes/optimize} 直接验证引擎。
     * 请求体结构见 {@link OptimizeRequest}。
     *
     * @return 200 + {@link VrpPlan}；入参非法返回 400（见 {@link #handleBadRequest}）
     */
    @PostMapping("/optimize")
    public ResponseEntity<VrpPlan> optimize(@RequestBody(required = false) OptimizeRequest request) {
        VrpProblemFactory.Spec spec = problemFactory.fromRequest(request);
        return ResponseEntity.ok(vrpService.solve(spec.problem(), spec.maxIterations()));
    }

    /**
     * 入参校验失败统一返回 400 + 中文原因（校验细节集中在 {@code VrpProblemFactory}）。
     *
     * <p>用 {@code String.valueOf} 兜底：{@code Map.of} 不接受 null 值，
     * 而异常的 message 允许为 null（历史上同类问题导致过 500，见 readme §9 #23）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", String.valueOf(ex.getMessage()));
        return ResponseEntity.badRequest().body(body);
    }
}
