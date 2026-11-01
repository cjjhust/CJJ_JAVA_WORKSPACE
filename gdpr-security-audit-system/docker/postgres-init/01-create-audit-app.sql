-- 01-create-audit-app.sql — 首次初始化数据卷时创建受限应用账号。
--
-- 只在 initdb 阶段（空数据卷）执行一次。此时业务表尚未创建（由 Flyway 迁移负责），
-- 因此这里**只创建角色**；表级授权放在迁移 V2__grant_audit_app.sql 中，
-- 保证权限在表存在之后授予，并对后续迁移新建的表同样生效。
--
-- 已有数据卷不会重跑此脚本；请手动执行对应 SQL 或执行 scripts/db-hardening.sql。

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_app') THEN
        CREATE ROLE audit_app LOGIN PASSWORD 'audit_app_password';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO audit_app;
