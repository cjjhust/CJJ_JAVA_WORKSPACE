package com.aslp.order.service;

import com.aslp.order.entity.OrderRecord;
import com.aslp.order.repository.OrderRepository;
import com.aslp.order.strategy.OrderPullResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单统一接入服务（M1）—— 策略拉取 → DTO 标准化 → 幂等落库 → 异常打标。
 *
 * <p><b>P2-1：拉取与落库分离</b>。原实现整条链路（含平台远程调用）标 {@code @Transactional}：
 * 重试 3 次 × 读超时 5s 期间，一个 DB 连接被「什么都没写」的事务占住，高并发下连接池会被
 * 这类空事务耗尽。现在 {@link #pullAndPersist()} 不再开事务 ——
 * 远程调用在事务外执行，落库交给 {@link OrderPersistService}（独立 bean，
 * {@code @Transactional} 才能经 AOP 代理生效）。幂等语义不变，见
 * {@link OrderPersistService} 的三层保障。
 *
 * <p>P1-1：同时把拉取结果写成 Micrometer 指标（Prometheus 端可见）：
 * <ul>
 *   <li>{@code aslp_order_pull_requests_total{platform,outcome}} —— 每次拉取的结局（success/failed/degraded）；</li>
 *   <li>{@code aslp_order_pull_orders_total{platform,result}} —— 订单条目级的 created/updated/flagged 计数。</li>
 * </ul>
 * 这两个指标能直接回答运维最常见的问题：
 * 「平台是挂了、还是被限流了（failed/degraded 上升）、还是根本没有新单（fetched=0）」。
 */
@Service
public class OrderPullService {

    private static final Logger log = LoggerFactory.getLogger(OrderPullService.class);

    /** P1-1：拉取请求结局计数器（带 platform / outcome 标签）。 */
    public static final String METRIC_PULL_REQUESTS = "aslp.order.pull.requests";

    /** P1-1：订单条目级计数器（带 platform / result 标签）。 */
    public static final String METRIC_PULL_ORDERS = "aslp.order.pull.orders";

    /**
     * P1-3：拉取的观测名（Zipkin 里就能看到这次拉取，以及它内部的策略调用）。
     * 经 Boot 自动注册的 MeterObservationHandler 也会产生同名 Timer 指标。
     */
    public static final String OBSERVATION_PULL = "aslp.order.pull";

    private final PlatformPullGateway platformPull;
    private final OrderPersistService persistService;
    private final OrderRepository repository;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public OrderPullService(PlatformPullGateway platformPull, OrderPersistService persistService,
                            OrderRepository repository, MeterRegistry meterRegistry,
                            ObservationRegistry observationRegistry) {
        this.platformPull = platformPull;
        this.persistService = persistService;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
    }

    /**
     * 单次拉取结果摘要。
     *
     * <p>P1-2 新增 {@code degraded}：区分「平台业务性失败」与「容错降级」
     * （重试耗尽 / 熔断打开 / 舱壁拒绝），便于上游与监控按不同等级处理。
     */
    public record PullSummary(
            String platform,
            boolean success,
            int fetched,
            int created,
            int updated,
            int flagged,
            String errorMsg,
            boolean degraded
    ) {
    }

    /**
     * 执行一次拉取并落库。
     *
     * <p><b>本方法不开事务</b>：远程调用（最坏数十秒）在事务外执行，
     * 落库事务由 {@link OrderPersistService#persist} 自管（秒级）。
     */
    public PullSummary pullAndPersist() {
        Observation observation = Observation.createNotStarted(OBSERVATION_PULL, observationRegistry).start();
        // openScope 会把 traceId/spanId 放进 MDC：日志里能直接搜到同一条链路
        try (Observation.Scope ignored = observation.openScope()) {
            PullSummary summary = doPullAndPersist();

            // 低基数：平台 + 结局（会同时出现在 span 与指标标签上）
            observation.lowCardinalityKeyValue("platform", tagValue(summary.platform()));
            observation.lowCardinalityKeyValue("outcome",
                    summary.success() ? "success" : (summary.degraded() ? "degraded" : "failed"));
            // 高基数：只进 span（跟着指标走会炸时间序列）
            observation.highCardinalityKeyValue("fetched", String.valueOf(summary.fetched()));
            observation.highCardinalityKeyValue("created", String.valueOf(summary.created()));
            observation.highCardinalityKeyValue("updated", String.valueOf(summary.updated()));
            observation.highCardinalityKeyValue("flagged", String.valueOf(summary.flagged()));
            if (summary.errorMsg() != null) {
                observation.highCardinalityKeyValue("errorMsg", summary.errorMsg());
            }
            return summary;
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    /** 实际拉取与落库逻辑（放外层只负责埋点与作用域管理）。 */
    private PullSummary doPullAndPersist() {
        // P1-2：平台调用统一走容错网关（重试 -> 熔断 -> 舱壁 -> 降级回退），此处拿到的一定是结果而非异常
        OrderPullResult result = platformPull.pull();

        if (!result.success()) {
            log.warn("[订单接入] 平台 {} 拉取失败（degraded={}）：{}",
                    result.platform(), result.degraded(), result.errorMsg());
            // P1-1：降级与业务性失败分开打标 —— 前者是自家熔断/重试耗尽，后者是平台侧返回错误
            countPullRequests(result.platform(), result.degraded() ? "degraded" : "failed");
            return new PullSummary(result.platform(), false, 0, 0, 0, 0, result.errorMsg(), result.degraded());
        }

        // P2-1：落库走独立 bean（@Transactional 生效）；幂等三层保障见 OrderPersistService
        OrderPersistService.PersistCounts counts = persistService.persist(result);

        log.info("[订单接入] 平台 {}：拉取 {} 条，新增 {}，更新 {}，异常打标 {}",
                result.platform(), result.orders().size(),
                counts.created(), counts.updated(), counts.flagged());

        // P1-1：一次拉取只记 1 次请求结局；条目级计数用 increment(amount) 一次性上报，
        // 避免在大循环里反复查表（每次 counter(...) 都是一次带锁的查表）
        countPullRequests(result.platform(), "success");
        countPullOrders(result.platform(), "created", counts.created());
        countPullOrders(result.platform(), "updated", counts.updated());
        countPullOrders(result.platform(), "flagged", counts.flagged());

        return new PullSummary(result.platform(), true,
                result.orders().size(), counts.created(), counts.updated(), counts.flagged(), null, false);
    }

    /** P1-1：拉取请求结局计数。 */
    private void countPullRequests(String platform, String outcome) {
        meterRegistry.counter(METRIC_PULL_REQUESTS, "platform", tagValue(platform), "outcome", outcome)
                .increment();
    }

    /** P1-1：订单条目级计数。 */
    private void countPullOrders(String platform, String result, int amount) {
        if (amount <= 0) {
            // 不预先创建零值计数器：否则每次重启都会充满一堆数值为 0 的时间序列
            return;
        }
        meterRegistry.counter(METRIC_PULL_ORDERS, "platform", tagValue(platform), "result", result)
                .increment(amount);
    }

    /** 标签值不能为 null（Micrometer 会直接抛异常），未知平台统一记为 unknown。 */
    private static String tagValue(String platform) {
        return platform == null || platform.isBlank() ? "unknown" : platform;
    }

    /** 按订单号查询。 */
    @Transactional(readOnly = true)
    public OrderRecord findByOrderId(String orderId) {
        return repository.findByOrderId(orderId).orElse(null);
    }

    /** 客服修正异常订单：更新仓库编码并清除异常标签（M1 任务 1.3）。 */
    @Transactional
    public boolean correctOrder(String orderId, String warehouseCode, String status) {
        return repository.findByOrderId(orderId)
                .map(record -> {
                    if (warehouseCode != null && !warehouseCode.isBlank()) {
                        record.setWarehouseCode(OrderPersistService.normalizeWarehouse(warehouseCode));
                    }
                    if (status != null && !status.isBlank()) {
                        record.setStatus(status);
                    }
                    record.setErrorTag(null);
                    repository.save(record);
                    log.info("[订单修正] {} 已修正为 warehouse={}, status={}",
                            orderId, record.getWarehouseCode(), record.getStatus());
                    return true;
                })
                .orElse(false);
    }
}
