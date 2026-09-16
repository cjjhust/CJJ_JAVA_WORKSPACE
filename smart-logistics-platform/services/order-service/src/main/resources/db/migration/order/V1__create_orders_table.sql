-- =============================================================================
-- V1 — 订单表（M1 统一订单网关）
--
-- 归属：order-service
-- 历史表：flyway_schema_history_order（与 inventory-service 各自独立）
--
-- 迁移约定：
--   1) 已应用的迁移文件不可修改（Flyway 以 checksum 校验），变更请新增 Vn+1；
--   2) 表结构必须与 JPA 实体严格对齐，否则 ddl-auto=validate 启动即失败；
--   3) 使用 IF NOT EXISTS 以便从「旧 init 脚本」平滑过渡（Flyway 仍按版本跟踪）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS orders (
    id             BIGSERIAL    PRIMARY KEY,
    order_id       VARCHAR(64)  NOT NULL UNIQUE,
    platform       VARCHAR(32)  NOT NULL,
    product        VARCHAR(200),
    warehouse_code VARCHAR(50)  NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    error_tag      VARCHAR(100),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ,
    version        BIGINT
);

CREATE INDEX IF NOT EXISTS idx_orders_platform_status ON orders (platform, status);
CREATE INDEX IF NOT EXISTS idx_orders_warehouse       ON orders (warehouse_code);

COMMENT ON TABLE  orders                IS 'M1 统一订单网关：各平台异构报文的标准化落库表';
COMMENT ON COLUMN orders.order_id       IS '平台订单号，全局唯一，用于幂等导入';
COMMENT ON COLUMN orders.error_tag      IS '异常标签：ADDRESS_INVALID / POSTCODE_MISSING / MATCH_FAILED';
COMMENT ON COLUMN orders.warehouse_code IS '履约仓库：Bruchsal（总仓）/ Mönchengladbach（分仓）';
COMMENT ON COLUMN orders.version        IS '乐观锁版本号，防止客服修正与自动同步并发覆盖';
