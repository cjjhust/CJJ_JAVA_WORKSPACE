package com.aslp.inventory.task;

import com.aslp.inventory.service.OrderReservationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * P2-4 — 库存最终一致性：超时预占释放补偿任务。
 *
 * <p>业务背景：订单经 {@code order.sync} 事件预占库存（可用 → 锁定）后，
 * 若长时间未进入支付确认（commit），该批库存会被永久占死 —— 促销期这就是「有货但卖不出」。
 * 本任务周期性扫描超时未提交的预占，执行释放（锁定 → 可用），实现三态闭环：
 *
 * <pre>可用 ──预占──▶ 锁定（RESERVED）──支付确认──▶ COMMITTED（履约扣减）
 *                    ▲                            │
 *                    └──── 超时释放（本任务）◀──────┘（未支付路径）</pre>
 *
 * <p><b>幂等安全</b>：释放动作与手动触发共用 {@code OrderReservationService#release}，
 * 状态机（RESERVED → RELEASED）保证同一订单不会被重复释放回双份库存；
 * 释放失败（如锁定量异常）保留 RESERVED，下一轮再试——宁可晚释放，不可错释放。
 */
@Component
public class ReservationReleaseTask {

    private static final Logger log = LoggerFactory.getLogger(ReservationReleaseTask.class);

    /** P2-4：释放补偿指标（outcome = released / none）。 */
    public static final String METRIC_RELEASE_RUNS = "aslp.reservation.release.runs";

    private final OrderReservationService reservationService;
    private final MeterRegistry meterRegistry;
    private final long maxAgeMinutes;
    private final boolean enabled;

    public ReservationReleaseTask(OrderReservationService reservationService,
                                  MeterRegistry meterRegistry,
                                  @Value("${aslp.reservation.max-age-minutes:30}") long maxAgeMinutes,
                                  @Value("${aslp.reservation.release-task-enabled:true}") boolean enabled) {
        this.reservationService = reservationService;
        this.meterRegistry = meterRegistry;
        this.maxAgeMinutes = maxAgeMinutes;
        this.enabled = enabled;
    }

    /** 每 5 分钟扫描一次（与预占的支付窗口 30 分钟相比，扫描粒度足够密）。 */
    @Scheduled(fixedDelayString = "${aslp.reservation.release-scan-ms:300000}", initialDelay = 60_000)
    public void releaseExpiredReservations() {
        if (!enabled) {
            return;
        }
        int released = reservationService.releaseExpired(Duration.ofMinutes(maxAgeMinutes));
        meterRegistry.counter(METRIC_RELEASE_RUNS, "outcome", released > 0 ? "released" : "none")
                .increment();
        if (released > 0) {
            log.info("[预占释放] 本轮释放 {} 笔超时预占（阈值 {} 分钟）", released, maxAgeMinutes);
        }
    }
}
