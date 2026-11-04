package com.aslp.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * P2-3 — 订单库存预占记录（异步流水线的幂等基石）。
 *
 * <p>同一 {@code orderId} 全局唯一（数据库 UNIQUE 兜底）：Kafka 至少一次投递 +
 * 消费重试会让同一事件多次到达，重复消费在此被挡住。
 * 该记录同时是 P2-4 超时释放补偿任务的事实源（{@code status=RESERVED} 且超时 → 释放）。
 */
@Entity
@Table(name = "order_reservation")
public class OrderReservation {

    public enum Status {
        /** 已预占（可用库存已扣减，等待支付确认）。 */
        RESERVED,
        /** 已释放（超时未支付 / 订单取消，库存回到可用）。 */
        RELEASED,
        /** 已提交（支付成功，库存转入履约扣减）。 */
        COMMITTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 平台订单号（幂等键，UNIQUE）。 */
    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "warehouse", nullable = false, length = 32)
    private String warehouse;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "status", nullable = false, length = 16)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)   // 与 V203 的 VARCHAR(16) 对齐（默认 ordinal 会映射成 smallint）
    private Status status = Status.RESERVED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "released_at")
    private Instant releasedAt;

    protected OrderReservation() {
        // JPA
    }

    public OrderReservation(String orderId, String sku, String warehouse, int quantity) {
        this.orderId = orderId;
        this.sku = sku;
        this.warehouse = warehouse;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getSku() {
        return sku;
    }

    public String getWarehouse() {
        return warehouse;
    }

    public int getQuantity() {
        return quantity;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void markReleased() {
        this.status = Status.RELEASED;
        this.releasedAt = Instant.now();
    }
}
