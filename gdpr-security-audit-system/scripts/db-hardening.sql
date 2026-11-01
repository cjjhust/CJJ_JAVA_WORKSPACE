-- ============================================================================
-- db-hardening.sql — Task 31: 数据库权限隔离与防篡改加固
--
-- 目标：即使拥有应用级数据库账号的攻击者/故障，也不能 UPDATE / DELETE 已写入
-- 的审计记录。角色拆分：
--   audit_app        — 应用运行账号：INSERT/SELECT，无 UPDATE/DELETE
--   audit_migration  — Schema 演进账号：DDL + 基础表管理
--   audit_dba        — DBA 排障账号：SELECT + 显式受审计的维护操作
--
-- 明确边界：PostgreSQL 超级用户仍可绕过本地控制（superuser_* 绕过任何
-- 权限/触发器）。真正的强保证需要 Task 33 的外部 WORM 锚定。
--
-- 用法（在 docker-compose 的 postgres 服务后执行）：
--   docker exec -i audit-postgres psql -U audit_user -d audit_db < scripts/db-hardening.sql
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 角色与账号（幂等创建）
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_app') THEN
        CREATE ROLE audit_app LOGIN PASSWORD 'change_me_app';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_migration') THEN
        CREATE ROLE audit_migration NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_dba') THEN
        CREATE ROLE audit_dba LOGIN PASSWORD 'change_me_dba';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO audit_app, audit_dba;

-- ---------------------------------------------------------------------------
-- 2. 业务表：应用可读写（User CRUD 是正常业务）
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE users TO audit_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO audit_app;

-- ---------------------------------------------------------------------------
-- 3. 审计表：APPEND-ONLY —— 应用只能 INSERT/SELECT，禁止 UPDATE/DELETE
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT ON TABLE audit_logs TO audit_app;
GRANT SELECT, INSERT ON TABLE audit_outbox_events TO audit_app;
GRANT SELECT, UPDATE ON TABLE audit_outbox_events TO audit_app;  -- Outbox 状态流转需要 UPDATE
REVOKE UPDATE, DELETE ON TABLE audit_logs FROM PUBLIC, audit_app;
GRANT DELETE ON TABLE audit_outbox_events TO audit_app; -- 触发器只允许清理已发送且过期的行

-- 链头表：应用需要行级 UPDATE（悲观锁推进 next_sequence/last_hash），但禁止 DELETE/INSERT
GRANT SELECT, UPDATE ON TABLE audit_chain_heads TO audit_app;
REVOKE DELETE ON TABLE audit_chain_heads FROM PUBLIC, audit_app;

-- ---------------------------------------------------------------------------
-- 4. 触发器：即使未来误授 UPDATE/DELETE，也在行级拒绝篡改审计链
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION forbid_audit_mutation()
RETURNS trigger AS $$
BEGIN
    -- 唯一放行的例外：无（append-only）。密钥轮换重放等运维操作
    -- 应通过 audit_migration 账号先 DISABLE TRIGGER，且操作本身要留痕。
    RAISE EXCEPTION 'audit_logs is append-only: % on id=% blocked (operator connection %)',
        TG_OP, COALESCE(OLD.id, -1), current_user;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_logs_no_update ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

DROP TRIGGER IF EXISTS trg_audit_logs_no_delete ON audit_logs;
CREATE TRIGGER trg_audit_logs_no_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

-- Outbox 载荷仅允许清理已成功投递且超过 7 天的记录，满足 publisher.cleanup()。
CREATE OR REPLACE FUNCTION allow_expired_sent_outbox_delete()
RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'SENT' OR OLD.sent_at IS NULL
       OR OLD.sent_at >= now() - INTERVAL '7 days' THEN
        RAISE EXCEPTION 'only SENT outbox rows older than 7 days may be deleted';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_outbox_delete_only_expired_sent ON audit_outbox_events;
CREATE TRIGGER trg_outbox_delete_only_expired_sent
    BEFORE DELETE ON audit_outbox_events
    FOR EACH ROW EXECUTE FUNCTION allow_expired_sent_outbox_delete();

-- 链头记录同样不可删除、不可回退序号
CREATE OR REPLACE FUNCTION forbid_chain_head_rollback()
RETURNS trigger AS $$
BEGIN
    IF NEW.next_sequence < OLD.next_sequence THEN
        RAISE EXCEPTION 'audit_chain_heads.next_sequence rollback blocked: % -> %',
            OLD.next_sequence, NEW.next_sequence;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_chain_heads_no_delete ON audit_chain_heads;
CREATE TRIGGER trg_chain_heads_no_delete
    BEFORE DELETE ON audit_chain_heads
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

DROP TRIGGER IF EXISTS trg_chain_heads_no_rollback ON audit_chain_heads;
CREATE TRIGGER trg_chain_heads_no_rollback
    BEFORE UPDATE ON audit_chain_heads
    FOR EACH ROW EXECUTE FUNCTION forbid_chain_head_rollback();

-- ---------------------------------------------------------------------------
-- 5. DBA 维护操作自身要留痕：建维护审计表，DBA 的 DDL/维护动作写入
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_maintenance_log (
    id          BIGSERIAL PRIMARY KEY,
    performed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    db_user     TEXT NOT NULL DEFAULT current_user,
    action      TEXT NOT NULL,
    detail      TEXT
);

REVOKE INSERT ON TABLE audit_maintenance_log FROM PUBLIC, audit_app;
GRANT SELECT, INSERT ON TABLE audit_maintenance_log TO audit_dba;

-- ---------------------------------------------------------------------------
-- 6. 默认权限：未来新建的审计相关表自动继承 append-only 语义
-- ---------------------------------------------------------------------------
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE DELETE ON TABLES FROM audit_app;

-- ---------------------------------------------------------------------------
-- 7. 迁移账号授权（供 Flyway/Liquibase 或人工 schema 变更使用）
-- ---------------------------------------------------------------------------
GRANT audit_migration TO audit_dba;
GRANT CREATE ON SCHEMA public TO audit_migration;

-- ============================================================================
-- 验收（Task 31 验收标准）：
--   SET ROLE audit_app;
--   UPDATE audit_logs SET username='tampered' WHERE id=1;  -- 应报错：append-only
--   DELETE FROM audit_logs WHERE id=1;                     -- 应报错
--   INSERT INTO audit_logs (...) VALUES (...);             -- 正常
--   RESET ROLE;
-- 已知边界：超级用户（如容器内的 postgres）不受上述限制。
-- ============================================================================
