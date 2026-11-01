-- V1__init.sql — 初始 Schema（替代 hibernate ddl-auto=update）
-- 表：users（业务演示，含 PII）、audit_logs（审计主表）、audit_chain_heads（链头）、
--     audit_outbox_events（Transactional Outbox）、audit_maintenance_log（DBA 维护留痕）

-- ============ 业务表 ============
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    first_name  VARCHAR(255) NOT NULL,
    last_name   VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    phone       VARCHAR(255),
    iban        VARCHAR(255),
    department  VARCHAR(255),
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- ============ 审计主表（append-only） ============
CREATE TABLE IF NOT EXISTS audit_logs (
    id               BIGSERIAL PRIMARY KEY,
    timestamp        TIMESTAMP NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    username         VARCHAR(255) NOT NULL,
    user_roles       VARCHAR(255),
    action           VARCHAR(32) NOT NULL,
    resource_type    VARCHAR(255) NOT NULL,
    resource_id      VARCHAR(255),
    request_method   VARCHAR(16),
    request_uri      VARCHAR(512),
    request_body     TEXT,
    response_status  INTEGER,
    client_ip        VARCHAR(64),
    user_agent       VARCHAR(512),
    duration         BIGINT,
    success          BOOLEAN,
    error_message    TEXT,
    chain_sequence   BIGINT UNIQUE,
    previous_hash    VARCHAR(64),
    record_hash      VARCHAR(64),
    signing_key_id   VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_audit_user_time   ON audit_logs (user_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_action_time ON audit_logs (action, timestamp);

-- ============ 审计链头（悲观锁推进） ============
CREATE TABLE IF NOT EXISTS audit_chain_heads (
    stream_id     VARCHAR(100) PRIMARY KEY,
    next_sequence BIGINT NOT NULL,
    last_hash     VARCHAR(64) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT NOT NULL DEFAULT 0
);

-- ============ Transactional Outbox（多实例租约领取） ============
CREATE TABLE IF NOT EXISTS audit_outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    audit_log_id    BIGINT NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    sent_at         TIMESTAMPTZ,
    lease_owner     VARCHAR(100),
    lease_until     TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON audit_outbox_events (status, created_at);

-- ============ DBA 维护留痕 ============
CREATE TABLE IF NOT EXISTS audit_maintenance_log (
    id           BIGSERIAL PRIMARY KEY,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    db_user      TEXT NOT NULL DEFAULT current_user,
    action       TEXT NOT NULL,
    detail       TEXT
);

-- ============ 种子数据（幂等） ============
INSERT INTO users (id, first_name, last_name, email, phone, iban, department, created_at)
SELECT 1, 'Max', 'Mustermann', 'max.mustermann@example.de', '+49 170 1234567', 'DE89370400440532013000', 'Engineering', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 1);

INSERT INTO users (id, first_name, last_name, email, phone, iban, department, created_at)
SELECT 2, 'Anna', 'Schmidt', 'anna.schmidt@company.de', '+49-170-9876543', 'FR7630006000011234567890189', 'Finance', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 2);

INSERT INTO users (id, first_name, last_name, email, phone, iban, department, created_at)
SELECT 3, 'Jonas', 'Weber', 'jonas.weber@mail.company.co.uk', '0170 5551234', NULL, 'Consulting', now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = 3);

SELECT setval('users_id_seq', GREATEST((SELECT MAX(id) FROM users), 1));

INSERT INTO audit_chain_heads (stream_id, next_sequence, last_hash, updated_at, version)
VALUES ('global', 1, repeat('0', 64), now(), 0)
ON CONFLICT (stream_id) DO NOTHING;
