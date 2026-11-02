package com.aslp.route.tracking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * P1-6：运单轨迹快照（每单一行，UPSERT 键 = 单号）。
 *
 * <p>回答的问题是「这个包裹<b>最后被看到</b>是什么状态、什么时候」——
 * 全量事件流水在承运商侧，本地存快照就足以支撑"卡住了吗"的判定。
 *
 * <ul>
 *   <li>{@code lastEventAt} 来自承运商（物理事实），是"卡住"判定的时钟起点，
 *       不是本地查询时间（那只能证明"我们查过"）；</li>
 *   <li>{@code terminal} = DELIVERED 等终态，扫描只看 {@code terminal=false} 的行；</li>
 *   <li>{@code alertedAt} 记上次告警时间：告警只发一次，重复扫描不重复发（幂等）。</li>
 * </ul>
 */
@Entity
@Table(name = "tracking_snapshot")
public class TrackingSnapshotRecord {

    @Id
    @Column(name = "tracking_number", length = 64, nullable = false)
    private String trackingNumber;

    @Column(nullable = false, length = 16)
    private String carrier;

    /** 归一化状态（{@link ShipmentState} 的 name()）。 */
    @Column(nullable = false, length = 32)
    private String state;

    @Column(name = "last_location", length = 120)
    private String lastLocation;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(nullable = false)
    private boolean terminal = false;

    @Column(name = "alerted_at")
    private Instant alertedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;

    public TrackingSnapshotRecord() {
    }

    /** 从一次查询结果构造快照（terminal 判定见 {@link ShipmentState} 语义）。 */
    public static TrackingSnapshotRecord from(TrackingView view) {
        TrackingSnapshotRecord r = new TrackingSnapshotRecord();
        r.trackingNumber = view.trackingNumber();
        r.carrier = view.carrier().name();
        r.state = view.state().name();
        r.lastLocation = view.lastLocation();
        r.lastEventAt = view.lastEventAt();
        r.terminal = isTerminal(view.state());
        return r;
    }

    /** 终态：不再可能变化，扫描时跳过。 */
    public static boolean isTerminal(ShipmentState state) {
        return state == ShipmentState.DELIVERED || state == ShipmentState.EXCEPTION;
    }

    /** UPSERT 更新：单号已存在时，把新快照的字段抄进来（保留 alertedAt 幂等标记）。 */
    public void updateFrom(TrackingView view) {
        this.carrier = view.carrier().name();
        this.state = view.state().name();
        this.lastLocation = view.lastLocation();
        this.lastEventAt = view.lastEventAt();
        this.terminal = isTerminal(view.state());
        this.updatedAt = Instant.now();
    }

    /** 该包裹是否已超过 {@code stuckDays} 天无新事件（且非终态）。 */
    public boolean isStuckSince(int stuckDays, Instant now) {
        return !terminal && lastEventAt != null
                && lastEventAt.isBefore(now.minus(java.time.Duration.ofDays(stuckDays)));
    }

    /** 是否还应发告警：从未告警过才发（幂等）。 */
    public boolean needsAlert() {
        return !terminal && alertedAt == null;
    }

    public void markAlerted(Instant now) {
        this.alertedAt = now;
    }

    public String getTrackingNumber() { return trackingNumber; }
    public String getCarrier() { return carrier; }
    public String getState() { return state; }
    public String getLastLocation() { return lastLocation; }
    public Instant getLastEventAt() { return lastEventAt; }
    public boolean isTerminal() { return terminal; }
    public Instant getAlertedAt() { return alertedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
