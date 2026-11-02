-- =============================================================================
-- P2-8：数据库拆分初始化（PostgreSQL 首次启动时由 docker-entrypoint-initdb.d 执行）
--
-- 语义：database per service —— 每个服务一个独立 database（同一 PG 实例）。
-- 这不是「拆容器」：仍然只有 1 个 PG 进程、1 个容器，内存开销几乎不变
-- （每库只是多一份 catalog 元数据），Mac mini 16GB 完全扛得住。
-- 拆开的意义：模式边界从「约定」（独立 Flyway 历史表）升级为「物理隔离」——
-- 一个服务的迁移脚本再也触碰不到另一个服务的表。
--
-- 共享库 aslp 保留：单体拓扑（app-monolith）合并在单库跑多套迁移。
-- =============================================================================

CREATE DATABASE aslp_order;
CREATE DATABASE aslp_inventory;
CREATE DATABASE aslp_auth;
CREATE DATABASE aslp_route;
