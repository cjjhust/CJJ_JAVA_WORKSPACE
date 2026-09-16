-- =============================================================================
-- V1 — 库存表（M2 多仓联动库存管理）
--
-- 归属：inventory-service
-- 历史表：flyway_schema_history_inventory（与 order-service 各自独立）
-- =============================================================================

CREATE TABLE IF NOT EXISTS inventory (
    id             BIGSERIAL    PRIMARY KEY,
    sku            VARCHAR(100) NOT NULL,
    warehouse_code VARCHAR(50)  NOT NULL,
    available_qty  INTEGER      NOT NULL DEFAULT 0,
    locked_qty     INTEGER      NOT NULL DEFAULT 0,
    unit_price     NUMERIC(12, 2),
    version        BIGINT
);

CREATE INDEX IF NOT EXISTS idx_inventory_sku_warehouse ON inventory (sku, warehouse_code);

COMMENT ON TABLE  inventory               IS 'M2 多仓库存明细：可用库存 / 锁定库存双状态';
COMMENT ON COLUMN inventory.available_qty IS '可用库存（可售）；下单预占时减少';
COMMENT ON COLUMN inventory.locked_qty    IS '锁定库存（已下单未出库）；支付成功后转为扣减';
COMMENT ON COLUMN inventory.version       IS '乐观锁版本号，与 Redisson 分布式锁形成双层防超卖';
