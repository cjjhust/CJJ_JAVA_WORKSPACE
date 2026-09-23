package com.aslp.order.entity;

import com.aslp.order.statemachine.OrderStates;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 订单状态机的事件轨迹（M4 持久化，只追加不改）。
 *
 * <p><b>为什么连「被拒绝的转换」也要落库</b>：客服场景里最常见的一类问题就是
 * 「我点了按钮，为什么没生效？」—— 只有记录 {@code accepted=false} 的尝试，
 * 才能回答「谁在什么时候试图做什么、被状态机拒绝了」，而不是只能回答「现在是什么状态」。
 *
 * <p><b>为什么 event 存字符串而不是枚举</b>：这张表是**历史事实**。
 * 枚举一旦重命名或删除某个常量，历史行就会因为无法反序列化而读不出来
 * （{@code @Enumerated} 遇到未知常量会抛异常）。审计日志应当能经受代码演进。
 * {@code fromState}/{@code toState} 保留枚举，因为它们的值域是稳定的状态集合。
 *
 * <p><b>为什么这张表刻意没有指向 {@code order_state} 的外键</b>：
 * {@code reset} 的实现就是「删除状态行 + 记一条 RESET 审计」——
 * 若加了带 {@code ON DELETE CASCADE} 的外键，复位会把审计一起删掉（等于毁掉历史）；
 * 不加外键则事件轨迹独立于状态行的生命周期，这也是「审计表不该被业务操作影响」的通则。
 */
@Entity
@Table(name = "order_state_event")
public class OrderStateEventRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Column(name = "event", length = 32, nullable = false)
    private String event;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32, nullable = false)
    private OrderStates fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", length = 32, nullable = false)
    private OrderStates toState;

    @Column(name = "accepted", nullable = false)
    private boolean accepted;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OrderStateEventRecord() {
        // JPA 需要无参构造
    }

    public OrderStateEventRecord(String orderId, String event, OrderStates fromState,
                                 OrderStates toState, boolean accepted, Instant occurredAt) {
        this.orderId = orderId;
        this.event = event;
        this.fromState = fromState;
        this.toState = toState;
        this.accepted = accepted;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getEvent() {
        return event;
    }

    public OrderStates getFromState() {
        return fromState;
    }

    public OrderStates getToState() {
        return toState;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
