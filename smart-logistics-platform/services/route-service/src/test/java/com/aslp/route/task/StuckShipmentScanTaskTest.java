package com.aslp.route.task;

import com.aslp.route.tracking.Carrier;
import com.aslp.route.tracking.ShipmentState;
import com.aslp.route.tracking.TrackingSnapshotRecord;
import com.aslp.route.tracking.TrackingSnapshotRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 「包裹卡住 N 天」扫描任务单测（P1-6）：卡住判定 + 告警幂等 + 终态跳过。
 */
class StuckShipmentScanTaskTest {

    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

    private static TrackingSnapshotRecord snapshot(String number, String state, Instant lastEventAt, boolean terminal) {
        TrackingSnapshotRecord r = new TrackingSnapshotRecord();
        // 通过反射不可取；直接用 from() 构造再改字段 —— 简化：用 from 造一个再按需覆写
        r.updateFrom(new com.aslp.route.tracking.TrackingView(number, Carrier.DHL,
                ShipmentState.valueOf(state), "raw", "desc", "Bruchsal", lastEventAt, null,
                List.of(), false));
        if (terminal) {
            r.updateFrom(new com.aslp.route.tracking.TrackingView(number, Carrier.DHL,
                    ShipmentState.DELIVERED, "raw", "desc", "Bruchsal", lastEventAt, null, List.of(), false));
        }
        return r;
    }

    @Test
    @DisplayName("last_event_at 超过 3 天且未告警过 → 告警一次并标记 alerted_at")
    void stuckShipmentIsAlertedOnce() {
        TrackingSnapshotRecord stuck = snapshot("T-1", "IN_TRANSIT", NOW.minus(java.time.Duration.ofDays(4)), false);
        TrackingSnapshotRepository repo = Mockito.mock(TrackingSnapshotRepository.class);
        when(repo.findByTerminalFalse()).thenReturn(List.of(stuck));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StuckShipmentScanTask task = new StuckShipmentScanTask(repo, new SimpleMeterRegistry(), 3);
        int alerted = task.scanOnce(NOW);

        assertEquals(1, alerted);
        assertNotNull(stuck.getAlertedAt(), "告警后必须打上 alerted_at 标记（幂等）");
    }

    @Test
    @DisplayName("已告警过 / 未超期 / 终态 → 不重复告警")
    void noDuplicateAlerts() {
        TrackingSnapshotRecord alreadyAlerted =
                snapshot("T-2", "IN_TRANSIT", NOW.minus(java.time.Duration.ofDays(5)), false);
        alreadyAlerted.markAlerted(NOW.minus(java.time.Duration.ofDays(1)));
        TrackingSnapshotRecord fresh =
                snapshot("T-3", "IN_TRANSIT", NOW.minus(java.time.Duration.ofHours(12)), false);
        TrackingSnapshotRecord delivered =
                snapshot("T-4", "DELIVERED", NOW.minus(java.time.Duration.ofDays(10)), true);

        TrackingSnapshotRepository repo = Mockito.mock(TrackingSnapshotRepository.class);
        when(repo.findByTerminalFalse()).thenReturn(List.of(alreadyAlerted, fresh, delivered));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StuckShipmentScanTask task = new StuckShipmentScanTask(repo, new SimpleMeterRegistry(), 3);

        assertEquals(0, task.scanOnce(NOW), "三种情况都不应产生新告警");
    }

    @Test
    @DisplayName("stuck-days 可配置：阈值 7 天时 4 天的包裹不算卡住")
    void thresholdIsConfigurable() {
        TrackingSnapshotRecord fourDaysOld =
                snapshot("T-5", "IN_TRANSIT", NOW.minus(java.time.Duration.ofDays(4)), false);
        TrackingSnapshotRepository repo = Mockito.mock(TrackingSnapshotRepository.class);
        when(repo.findByTerminalFalse()).thenReturn(List.of(fourDaysOld));

        StuckShipmentScanTask task = new StuckShipmentScanTask(repo, new SimpleMeterRegistry(), 7);

        assertEquals(0, task.scanOnce(NOW));
        Mockito.verify(repo, Mockito.never()).save(any());
    }
}
