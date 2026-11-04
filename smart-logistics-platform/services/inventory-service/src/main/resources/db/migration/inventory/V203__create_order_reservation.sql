-- =============================================================================
-- P2-3 / P2-4：订单库存预占记录（异步流水线的幂等基石）
--
-- 职责：
--   * order-service 经 order.sync topic 广播新建订单，inventory-service 消费后
--     在此落「预占记录」并扣减可用库存（可用 → 锁定）。
--   * UNIQUE(order_id) 是幂等的最终兜底：Kafka 至少一次投递 + 消费重试意味着
--     同一事件可能到达多次，重复消费靠唯一约束挡住（先查后插仍可能竞态）。
--   * 记录也是 P2-4 三态一致性中「锁定期」的事实源：超时未支付的任务扫描本表
--     released_at IS NULL 且 created_at 超阈值的行，执行释放补偿。
-- =============================================================================
CREATE TABLE order_reservation (
    id           BIGSERIAL PRIMARY KEY,
    order_id     VARCHAR(64) NOT NULL,
    sku          VARCHAR(64) NOT NULL,
    warehouse    VARCHAR(32) NOT NULL,
    quantity     INT         NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'RESERVED',  -- RESERVED / RELEASED / COMMITTED
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    released_at  TIMESTAMP   NULL,
    CONSTRAINT uq_order_reservation_order UNIQUE (order_id),
    CONSTRAINT ck_order_reservation_qty CHECK (quantity > 0)
);

CREATE INDEX idx_order_reservation_release_scan
    ON order_reservation (status, created_at)
    WHERE released_at IS NULL;
