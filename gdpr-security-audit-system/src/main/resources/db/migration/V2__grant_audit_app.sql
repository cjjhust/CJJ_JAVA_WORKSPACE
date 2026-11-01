-- V2__grant_audit_app.sql — 为受限运行账号 audit_app 授予最小表权限。
--
-- 为何放在迁移而不是 initdb 脚本：initdb 阶段表还不存在（V1 才建表），
-- 那时执行 GRANT 会因对象不存在而失败，导致 Application 运行账号无任何表权限。
--
-- 授权模型（对应 scripts/db-hardening.sql）：
--   users              : 业务表，正常读写
--   audit_logs         : 只允许 SELECT / INSERT（append-only，禁止 UPDATE / DELETE）
--   audit_outbox_events: 需要 UPDATE（状态流转）与 DELETE（仅清理已投递的旧记录）
--   audit_chain_heads  : 需要 SELECT / UPDATE（悲观锁推进链头），禁止 DELETE
-- 迁移本身以 DDL 账号（audit_user）执行，运行账号 audit_app 不持有 schema CREATE 权限。

GRANT USAGE ON SCHEMA public TO audit_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE users TO audit_app;

GRANT SELECT, INSERT ON TABLE audit_logs TO audit_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE audit_outbox_events TO audit_app;

GRANT SELECT, UPDATE ON TABLE audit_chain_heads TO audit_app;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO audit_app;

-- 后续迁移新建的表也自动获得最小读写权限（仍不含审计表更新/删除）
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT ON TABLES TO audit_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO audit_app;
