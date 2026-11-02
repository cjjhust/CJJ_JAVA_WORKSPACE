-- =============================================================================
-- V301 — 追踪事件表（P1-6「包裹卡住 N 天」告警的数据底座）
--
-- 归属：route-service（迁移段位 3xx，见 todo.md 缺陷 #58 后的段位约定：
--   auth=1xx / inventory=2xx / route=3xx / order 保留原 V1/V2 亦属 3xx 语义域）
--
-- 设计要点：
--   1) 一行 = 一次成功查询到的轨迹快照（含最新状态），UPSERT 键是单号 ——
--      「这个包裹最后被看到是什么状态、什么时候」，而不是全量事件流水
--      （全量流水在承运商侧，本地存快照即可回答"卡住了吗"）；
--   2) last_event_at 是"卡住"判定的时钟起点：它来自承运商（物理事实），
--      不是本地查询时间（那只会证明"我们查过"）；
--   3) terminal = DELIVERED / RETURNED 等终态标记，扫描只看 terminal=false 的行；
--   4) alerted_at 记上次告警时间：告警只发一次，重复扫描不重复发（幂等）。
-- =============================================================================

CREATE TABLE IF NOT EXISTS tracking_snapshot (
    id             BIGSERIAL    PRIMARY KEY,
    tracking_number VARCHAR(64) NOT NULL UNIQUE,
    carrier        VARCHAR(16)  NOT NULL,
    state          VARCHAR(32)  NOT NULL,
    last_location  VARCHAR(120),
    last_event_at  TIMESTAMPTZ,
    terminal       BOOLEAN      NOT NULL DEFAULT FALSE,
    alerted_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 乐观锁（实体 @Version；UPSERT 更新路径走 JPA save，需要该列）
    version        BIGINT
);

COMMENT ON TABLE tracking_snapshot IS
    'P1-6：运单轨迹快照（每单一行，UPSERT）。扫描 terminal=false 且 last_event_at 超过阈值的行即"卡住"。';

CREATE INDEX IF NOT EXISTS idx_tracking_stuck_scan
    ON tracking_snapshot (terminal, last_event_at)
    WHERE terminal = FALSE;
