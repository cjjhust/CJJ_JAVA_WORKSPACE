package com.aslp.order.entity;

import com.aslp.order.statemachine.OrderStates;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 订单状态机的「当前状态」（M4 持久化）。
 *
 * <p>与 {@code OrderRecord.status} 的区别：{@code OrderRecord.status} 是**履约流水线**的状态
 * （由平台拉取/客服修正驱动，值域是自由字符串），本表是**换标工单**的状态机状态
 * （值域是 {@link OrderStates} 枚举，只经状态机迁移推进）。两者语义不同，
 * 因此不合并到一张表 —— 否则一次状态机演示会污染真实订单的履约状态。
 *
 * <p>主键就是业务单号（不是自增 id）：状态是「每个订单恰好一行」的数据，
 * 用业务键做主键天然保证唯一，且省掉一次唯一索引查询。
 *
 * <p>{@code @Version} 乐观锁：两个并发请求同时推进同一订单时，后写者抛
 * {@code ObjectOptimisticLockingFailureException} 而不是静默覆盖 ——
 * 与 {@code InventoryItem} 用的是同一套并发保护思路。
 */
@Entity
@Table(name = "order_state")
public class OrderStateRecord {

    @Id
    @Column(name = "order_id", length = 64, nullable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", length = 32, nullable = false)
    private OrderStates currentState;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected OrderStateRecord() {
        // JPA 需要无参构造
    }

    public OrderStateRecord(String orderId, OrderStates currentState, Instant updatedAt) {
        this.orderId = orderId;
        this.currentState = currentState;
        this.updatedAt = updatedAt;
    }

    public String getOrderId() {
        return orderId;
    }

    public OrderStates getCurrentState() {
        return currentState;
    }

    public void setCurrentState(OrderStates currentState) {
        this.currentState = currentState;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
