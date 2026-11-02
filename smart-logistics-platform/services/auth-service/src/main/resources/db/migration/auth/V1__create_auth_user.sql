-- =============================================================================
-- P2-6：用户表（登录认证的唯一数据源）
--
-- 与 order / inventory 共用同一 PostgreSQL 数据库，但 Flyway 使用独立历史表
-- （flyway_schema_history_auth，见 application*.yml），避免互相锁表/混淆。
--
-- 种子账号（演示/开发/CI 冒烟脚本用，密码为 BCrypt 哈希）：
--   admin    / admin123    （角色 ADMIN，登录名以 admin 开头是历史脚本的约定，保留）
--   operator / operator123 （角色 OPERATOR）
--
-- 生产环境：这些种子账号必须在首次部署后立即修改密码或删除
-- （或改用环境变量关闭种子注入 —— 见 V2 迁移说明）。
-- =============================================================================

CREATE TABLE auth_user (
    username      VARCHAR(64)  PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);

CREATE INDEX idx_auth_user_role ON auth_user (role);

-- ---------------------------------------------------------------------------
-- 种子数据：哈希值由 BCryptPasswordEncoder 生成（strength=10，含随机盐），
-- 与测试夹具 AuthServiceTest 中使用的口令一一对应。
-- ---------------------------------------------------------------------------
INSERT INTO auth_user (username, password_hash, role, created_at) VALUES
    ('admin',    '$2a$10$vKBU4b410MkTXiXVtfnf8.L0ypsqlJNby8BP8NgC/qGZBtnzQVW32', 'ADMIN',    now()),
    ('operator', '$2a$10$XmSNeSZWRzajZC5wXxoVLO/tFFWTX0Q3uJUspXXUlICBe5dQJwpT.', 'OPERATOR', now());
