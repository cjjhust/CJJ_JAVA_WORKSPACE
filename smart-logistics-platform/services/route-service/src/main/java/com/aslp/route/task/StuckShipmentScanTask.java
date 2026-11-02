package com.aslp.route.task;

import com.aslp.route.tracking.TrackingSnapshotRecord;
import com.aslp.route.tracking.TrackingSnapshotRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * P1-6：「包裹卡住 N 天」定时扫描告警。
 *
 * <h3>它解决什么</h3>
 * 追踪查询是<b>拉</b>模型——没人查就没人知道包裹停了。客服的痛点恰恰是反过来的：
 * 「哪些包裹<b>应该</b>被打电话却还没人发现」。本任务把拉模型补成推模型：
 * 周期扫描非终态运单快照，{@code last_event_at} 超过阈值（默认 3 天）且未告警过的行，
 * 记 WARN 日志 + 打指标 {@code aslp.tracking.stuck}（Grafana 可对它做告警规则）。
 *
 * <h3>幂等与边界</h3>
 * <ul>
 *   <li>告警只发一次（{@code alerted_at} 标记）：扫描是周期性的，包裹不会因为
 *       「已经被提醒过」而加速——重复告警只会训练人忽略它；</li>
 *   <li>包裹恢复（来了新事件）后 {@code alerted_at} 语义不变：快照被 UPSERT 时保留
 *       该标记，避免「卡 3 天 → 动了一下 → 又卡 3 天」触发第二遍；</li>
 *   <li>异常单（EXCEPTION）是终态：它已经"被知道坏了"，属于工单流程，不属于"卡住"。</li>
 * </ul>
 */
@Component
public class StuckShipmentScanTask {

    private static final Logger log = LoggerFactory.getLogger(StuckShipmentScanTask.class);

    private final TrackingSnapshotRepository repository;
    private final MeterRegistry meterRegistry;
    private final int stuckDays;

    public StuckShipmentScanTask(TrackingSnapshotRepository repository,
                                 MeterRegistry meterRegistry,
                                 @Value("${aslp.tracking.stuck-days:3}") int stuckDays) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.stuckDays = stuckDays;
    }

    /** 每小时扫一次：包裹状态是分钟级变化，小时粒度对"卡 3 天"足够。 */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void scan() {
        int stuck = scanOnce(Instant.now());
        if (stuck > 0) {
            log.warn("[追踪] 本轮扫描发现 {} 个包裹卡住超过 {} 天", stuck, stuckDays);
        }
    }

    /** 一轮扫描（public 便于测试直调）：返回本轮新告警的运单数。 */
    public int scanOnce(Instant now) {
        // 预热带标签序列：与 TrackingService 的做法一致，让看板在首个卡住包裹出现前就有序列
        meterRegistry.counter("aslp.tracking.stuck", "carrier", "DHL");
        meterRegistry.counter("aslp.tracking.stuck", "carrier", "DPD");

        List<TrackingSnapshotRecord> candidates = repository.findByTerminalFalse();
        int alerted = 0;
        for (TrackingSnapshotRecord record : candidates) {
            if (record.isStuckSince(stuckDays, now) && record.needsAlert()) {
                log.warn("[追踪] 包裹卡住：{}（{}）状态 {}，最后事件 {}（超过 {} 天）",
                        record.getTrackingNumber(), record.getCarrier(), record.getState(),
                        record.getLastEventAt(), stuckDays);
                meterRegistry.counter("aslp.tracking.stuck", "carrier", record.getCarrier()).increment();
                record.markAlerted(now);
                repository.save(record);
                alerted++;
            }
        }
        return alerted;
    }
}
