-- =============================================================================
-- V2 — 订单状态机持久化（M4 技术亮点：FBA 退货换标）
--
-- 归属：order-service
-- 历史表：flyway_schema_history_order（与 inventory-service 各自独立）
--
-- 背景：原实现把每个订单的状态机实例放在进程内的 ConcurrentHashMap 里，
--       服务一重启状态即归零，多实例部署时各实例看到的状态也不一致。
--       本迁移把「当前状态」与「事件轨迹」落库，DB 成为唯一真相源。
--
-- 迁移约定：
--   1) 已应用的迁移文件不可修改（Flyway 以 checksum 校验），变更请新增 Vn+1；
--   2) 表结构必须与 JPA 实体严格对齐，否则 ddl-auto=validate 启动即失败；
--   3) 使用 IF NOT EXISTS 以便重复执行不报错（Flyway 仍按版本跟踪）。
-- =============================================================================

-- 当前状态（每个订单一行；缺行 = 尚未发生任何迁移，语义上等同 CREATED）
CREATE TABLE IF NOT EXISTS order_state (
    order_id      VARCHAR(64) PRIMARY KEY,
    current_state VARCHAR(32) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    -- 乐观锁：两个并发请求同时推进同一订单时，后写者失败而不是静默覆盖
    version       BIGINT      NOT NULL DEFAULT 0
);

-- 事件轨迹（只追加，不修改）：既是审计，也是「状态怎么走到今天」的答案
CREATE TABLE IF NOT EXISTS order_state_event (
    id          BIGSERIAL   PRIMARY KEY,
    order_id    VARCHAR(64) NOT NULL,
    -- 事件名按字符串存（不是枚举）：日志是历史事实，枚举重命名/删除不该让旧行读不出来
    event       VARCHAR(32) NOT NULL,
    from_state  VARCHAR(32) NOT NULL,
    to_state    VARCHAR(32) NOT NULL,
    -- false = 非法转换被拒绝。失败也要留痕：它回答「客户说他点过按钮，为什么没生效」
    accepted    BOOLEAN     NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

-- 按订单 + 自增 id 排序即为时间序（id 单调递增，避免同一毫秒内时间戳无法定序）
CREATE INDEX IF NOT EXISTS idx_order_state_event_order ON order_state_event (order_id, id);

COMMENT ON TABLE  order_state             IS 'M4 状态机当前状态（持久化，替代原进程内 ConcurrentHashMap）';
COMMENT ON COLUMN order_state.version     IS '乐观锁版本号：并发推进同一订单时后写者失败，避免静默覆盖';
COMMENT ON TABLE  order_state_event       IS 'M4 状态机事件轨迹（只追加）：审计 + 非法转换留痕';
COMMENT ON COLUMN order_state_event.accepted IS '是否发生合法转换；false 表示该事件被状态机拒绝';
