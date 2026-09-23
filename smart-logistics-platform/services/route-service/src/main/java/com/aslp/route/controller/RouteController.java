package com.aslp.route.controller;

import com.aslp.route.dto.OptimizeRequest;
import com.aslp.route.dto.VrpPlan;
import com.aslp.route.engine.VrpProblemFactory;
import com.aslp.route.service.TrackingService;
import com.aslp.route.service.VrpRouteService;
import com.aslp.route.tracking.Carrier;
import com.aslp.route.tracking.TrackingClientException;
import com.aslp.route.tracking.TrackingNotFoundException;
import com.aslp.route.tracking.TrackingUnavailableException;
import com.aslp.route.tracking.TrackingView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M3 路径优化 / 运费 / 尾程追踪 / 健康检查入口。
 *
 * <p>兼容 {@code /api/routes/**} 与 {@code /api/route/**} 两种前缀
 * （网关断言用的是前者，见 readme §9 #14）。
 */
@RestController
@RequestMapping({"/api/routes", "/api/route"})
public class RouteController {

    private static final Logger log = LoggerFactory.getLogger(RouteController.class);

    private final VrpRouteService vrpService;
    private final VrpProblemFactory problemFactory;
    private final TrackingService trackingService;

    public RouteController(VrpRouteService vrpService, VrpProblemFactory problemFactory,
                           TrackingService trackingService) {
        this.vrpService = vrpService;
        this.problemFactory = problemFactory;
        this.trackingService = trackingService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "route-service up",
            "engine", "jsprit VRP",
            "algorithm", "ruin & recreate (Schrimpf) + haversine km",
            "region", "Europe (DHL/DPD)",
            "tracking", "DHL + DPD 一单到底（归一化状态 + 有界缓存）"
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
     * 「一单到底」包裹追踪（P1-9：从骨架升级为真实 DHL/DPD 实现）。
     *
     * <p>承运商判定：不传 {@code carrier} 时按单号长度/前缀自动识别
     * （DHL 10/20 位数字或 JJD/JVGL/GM 前缀；DPD 14 位数字），识别不出来返回 400 并提示显式指定 ——
     * **不猜**，否则用户会拿着正确单号在错误的承运商上反复核对。
     *
     * <p>{@code refresh=true} 跳过本地缓存（默认 60s）：客户端"刷新"按钮与客服刚打完电话的场景
     * 需要看到最新状态，而不是缓存里那份。
     *
     * @return 200 + {@link TrackingView}；查无此单 404；承运商不可用 503；上游拒绝 502
     */
    @GetMapping("/tracking/{trackingNumber}")
    public ResponseEntity<TrackingView> tracking(
            @PathVariable String trackingNumber,
            @RequestParam(required = false) String carrier,
            @RequestParam(defaultValue = "false") boolean refresh) {

        Carrier explicit = null;
        if (carrier != null && !carrier.isBlank()) {
            try {
                explicit = Carrier.valueOf(carrier.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                // 非法承运商名 → 400（与"识别不出来"同一类错误：都是入参问题）
                throw new IllegalArgumentException(
                        "不支持的承运商：" + carrier + "（可选：" + java.util.Arrays.toString(Carrier.values()) + "）");
            }
        }
        return ResponseEntity.ok(trackingService.lookup(trackingNumber, explicit, refresh));
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

    /**
     * 查无此单 → <b>404</b>。
     *
     * <p>这是正常业务状态（单号打错 / 承运商还没录入），不是错误：
     * 前端应引导"请核对单号"，而不是像依赖故障那样提示"稍后重试"
     * —— 后者会让用户一直重试一个永远不存在的单号。
     */
    @ExceptionHandler(TrackingNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(TrackingNotFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "TRACKING_NOT_FOUND");
        body.put("carrier", ex.getCarrier().name());
        body.put("trackingNumber", ex.getTrackingNumber());
        body.put("message", "承运商查询不到该单号，请核对后重试");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 承运商不可用（5xx / 429 / 超时）→ <b>503</b>（依赖故障，可重试）。
     *
     * <p>带 {@code rateLimited} 与 {@code retryAfterSeconds}：限流与服务故障的处置不同
     * （限流要退避，故障要看对方状态页），把它们压成一个 503 会让调用方无法决策。
     */
    @ExceptionHandler(TrackingUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(TrackingUnavailableException ex) {
        log.warn("[追踪] 承运商不可用：{}", ex.getMessage(), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.isRateLimited() ? "TRACKING_RATE_LIMITED" : "TRACKING_UNAVAILABLE");
        body.put("carrier", ex.getCarrier().name());
        body.put("rateLimited", ex.isRateLimited());
        body.put("retryAfterSeconds", ex.getRetryAfterSeconds());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * 承运商拒绝请求（其他 4xx）→ <b>502</b>。
     *
     * <p>不返回 400 的原因：这一类的成因有两面 —— 单号格式确实非法（用户的问题），
     * 也可能是我们请求拼错/凭据过期（我们的问题）。返回 400 会误导用户去改一个本来正确的单号；
     * 502 如实表达"网关背后的这次上游调用没成"，并把上游原文带出来供排查。
     */
    @ExceptionHandler(TrackingClientException.class)
    public ResponseEntity<Map<String, Object>> handleUpstreamRejected(TrackingClientException ex) {
        log.error("[追踪] 承运商拒绝请求：{}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "TRACKING_UPSTREAM_REJECTED");
        body.put("carrier", ex.getCarrier().name());
        body.put("upstreamStatus", ex.getUpstreamStatus());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
}
