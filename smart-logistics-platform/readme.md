# Smart Logistics Platform (ASLP) — 海外仓智能物流系统

> 最后更新：2026-09-17 ｜ 版本 `1.0.0-SNAPSHOT`
> 状态：**M1–M4 全部交付 + P0 基线补齐（P0-1～P0-5）+ P1 阶段全部完成（P1-1 指标 / P1-1b 日志 / P1-2 限流容错 / P1-2b VRP 引擎 / P1-3 链路追踪 / P1-4 平台契约测试 / P1-5 邮件真实化 / P1-6 对象存储）；`mvn clean package -T 1C` 全绿（178 个单测全部通过，0 跳过），容器全量验证 18/18 就绪 + 端到端冒烟 96/96 通过**
> 包根：`com.aslp.*`（已脱敏，详见 §12） ｜ 构建：Maven 3.9+ “高铁模式”

---

## 1. 项目定位

面向德国海外仓（Bruchsal 总仓 + Mönchengladbach 分仓）的 B2B 履约中台，解决四类核心问题：

| 里程碑 | 业务命题 | 技术交付 |
|---|---|---|
| **M1** | 多平台订单（Amazon.de / eBay.de）统一接入、限流、异常修复 | 策略模式订单拉取 + JWT 认证服务 + ECharts 报表服务 |
| **M2** | 两仓库存强一致、大促防超卖、安全库存智能补货 | Redisson 分布式锁 + `@Version` 乐观锁 + 定时预警 + 补货邮件 |
| **M3** | 欧洲尾程（DHL/DPD）路径优化与运费精算 | jsprit VRP 引擎 + 运费规则引擎 + 轨迹追踪 |
| **M4** | B2B 前端 BFF 聚合、居家办公 VPN 安全接入、数据备份 | 网关 BFF 多条件分页 + Spring Security JWT 细粒度权限 + `pg_dump` 定时备份 |

---

## 2. 技术栈

| 层次 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21 LTS |
| 框架 | Spring Boot 3.3.0 / Spring Cloud 2023.0.3 |
| 网关 | Spring Cloud Gateway（WebFlux，端口 8080） |
| 安全 | Spring Security 6 + OAuth2 Resource Server + JJWT 0.12.5（RBAC） |
| 数据 | PostgreSQL 16 + Hibernate 6.5 + Redisson 3.27（分布式锁） |
| 消息 / 缓存 | Redis 7 + Apache Kafka 7.6.0（Confluent，ZooKeeper 模式） |
| 算法 | jsprit 1.8（VRP） |
| 可观测性 | Micrometer + Prometheus 注册表（每服务 `/actuator/prometheus`）+ Prometheus 2.54 + Grafana 11.3（数据源与看板以文件版本化管理） |
| 链路追踪 | Micrometer Tracing（Brave 桥接）+ Zipkin 3（每服务上报 span，W3C `traceparent` 上下文传播，traceId 自动进日志 MDC） |
| 日志聚合 | Promtail 3（经 **Docker API** 采集容器 stdout）+ Loki 3（tsdb+文件系统存储）+ Grafana 日志面板；日志里的 traceId 可一键跳 Zipkin |
| 外部平台集成 | Amazon SP-API 真实 HTTP 客户端（`RestClient` + LWA OAuth 2.0，显式 connect/read 超时，`NextToken` 分页）|
| 契约测试 | **WireMock 3.9（standalone 版）**：进程内模拟 SP-API（LWA 换令牌 / 分页 / 429 限流 / 超时 / 5xx / 4xx / 空结果）；另有容器版桩 `aslp_wiremock` 供端到端验证 |
| 邮件 | **MailHog 1.0.1**（真实 SMTP :1025 + Web 收件箱 :8025）+ **Thymeleaf 3**（HTML 模板）+ `MimeMessageHelper`（multipart/alternative） |
| 对象存储 | **MinIO**（S3 兼容，quay.io 镜像，:9000 API / :9001 控制台）+ 官方 Java SDK 8.5 + **OpenPDF 2.0**（面单/报关单 PDF 生成） |
| 构建 | **Maven 3.9+（“高铁模式” `-T 1C` 并行编译）** |
| 测试 | JUnit 5 + Spring Boot Test + Mockito + WireMock（契约） |
| 容器 | Docker Compose（容器名统一 `aslp_*`，网络 `aslp_net`） |

### 为什么生产用 Maven 而不是 Gradle

M4 开发机上 Gradle 的多核增量编译更快；但海外仓系统承载跨境 B2B 客户的**资产、运费计费与订单数据**，
需满足德国本地信息安全审计与 **Reproducible Builds（可重复构建）** 要求。`pom.xml` 的声明式静态特性
保证核心物流系统未来 5 年拥有 100% 的依赖可预测性，并天然杜绝构建脚本在编译期的远程执行风险。
**用编译时间的微小牺牲，换取绝对的软件供应链安全。**

---

## 3. 快速启动

### 3.1 构建（推荐：Maven 高铁模式）

```bash
# 并行编译 + 运行全部单元测试（-T 1C = 按 CPU 核心数并行）
mvn clean package -T 1C

# 仅构建某模块及其依赖
mvn clean package -pl services/order-service -am -T 1C
```

产物为 **可直接运行的 Spring Boot fat jar**（各模块 `target/*.jar`，含 `Main-Class`）。

### 3.2 方式 A：本地进程启动（推荐日常开发）

```bash
# 0) 清理上一轮遗留进程（否则端口被占用 → 服务启动报 webServerStartStop 失败）
pkill -9 -f smart-logistics-platform || true

# 1) 启动基础设施（PostgreSQL + Redis 为 inventory/order 依赖）
docker compose up -d aslp_postgres aslp_redis

# 2) 首次或需要重置种子数据时（会清空数据卷，随后由 01-schema.sql 自动重建）
docker compose down -v && docker compose up -d aslp_postgres

# 3) 一键冒烟测试：启动 6 个服务 → 校验网关路由 → 校验业务链路 → 自动清理
bash scripts/smoke-test.sh
```

### 3.3 方式 B：容器全量启动（推荐验证集成）

```bash
# 一键：构建 6 个服务镜像 → 启动基础设施 + 6 服务 + Prometheus + Grafana + Zipkin + Loki + Promtail + WireMock 契约桩 + MailHog + MinIO → 等就绪 → 跑 96 项断言
bash scripts/container-verify.sh

# 镜像已存在时跳过构建
bash scripts/container-verify.sh --no-build

# 手动分步
docker compose up -d --build
docker compose ps                 # 所有服务应为 healthy
docker compose down               # 停止（不加 -v 保留数据卷）
```

> 所有 Java 服务共用根目录 `Dockerfile`，通过 `build.args.MODULE` 选择模块；
> 依赖预下载层与 `MODULE` 无关，6 个镜像共享同一份缓存。
> 表结构与种子数据不依赖 `docker-entrypoint-initdb.d`，而是由服务启动时的 Flyway 迁移完成。

### 3.4 服务端口与路由

| 服务 | 端口 | 网关路由前缀 | 说明 |
|---|---|---|---|
| `gateway` | **8080** | — | 统一入口 + BFF（`/bff/orders/search`） |
| `order-service` | 8081 | `/api/orders/**` | 订单同步 + 状态机 |
| `inventory-service` | 8082 | `/api/inventory/**` | 库存 + 分布式锁（依赖 PG/Redis） |
| `route-service` | 8083 | `/api/routes/**`（兼容 `/api/route/**`） | VRP + 运费 + 追踪 |
| `auth-service` | 8084 | `/api/auth/**` | JWT 签发（RBAC） |
| `report-service` | 8085 | `/api/reports/**` | ECharts 看板数据 |
| `aslp_prometheus` | 9090 | —（直连） | P1-1 指标存储：抓取各服务 `/actuator/prometheus`，保留 7 天 |
| `aslp_grafana` | 3000 | —（直连） | P1-1 看板：浏览器直接打开 `http://localhost:3000`（演示环境已开匿名只读） |
| `aslp_zipkin` | 9411 | —（直连） | P1-3 链路追踪：`http://localhost:9411/zipkin/` 查链路（内存存储，重启即清） |
| `aslp_loki` | 3100 | —（直连） | P1-1b 日志存储：查询 API `/loki/api/v1/query_range`（文件系统存储，保留时间不设限） |
| `aslp_promtail` | 9080 | —（直连） | P1-1b 采集器：`/targets` 看采集目标，`/metrics` 看采集指标（`promtail_docker_target_entries_total`） |

每服务均暴露 `GET /actuator/health`；P1-1 起均额外暴露 `GET /actuator/prometheus`（Prometheus 文本格式指标）。

---

## 4. 接口速查

```bash
# 健康检查
curl http://localhost:8080/actuator/health

# ── M1 订单统一接入流水线 ──────────────────────────────
# 触发拉取（策略模式 → DTO 标准化 → 幂等落库 → 异常打标）
curl -X POST http://localhost:8080/api/orders/pull
# 多条件分页查询
curl "http://localhost:8080/api/orders?status=PAID&warehouseCode=Bruchsal&page=0&size=20"
# 汇总统计（供 BFF / 报表看板）
curl http://localhost:8080/api/orders/stats
# 客服远程修正异常订单（清除异常标签后重新进入履约流水线）
curl -X POST "http://localhost:8080/api/orders/AMZ-1003/correct?warehouseCode=Bruchsal&status=PAID"

# ── M1 认证 / M4 网关 VPN 接入 ─────────────────────────
curl -X POST "http://localhost:8080/api/auth/login?username=admin"

# ── M2 多仓库存（Redisson 锁 + 乐观锁）────────────────
curl -X POST "http://localhost:8080/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2"
# P0-5 查询：返回「SKU 汇总 + 各仓明细」；不带 warehouseCode 则返回全部仓库
curl "http://localhost:8080/api/inventory/AMZ-1001?warehouseCode=Bruchsal"
# P0-5 释放锁定库存（支付失败 / 订单取消 / 超时未支付）
curl -X POST "http://localhost:8080/api/inventory/release?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2"

# ── M4 订单状态机（FBA 退货换标，状态按订单号隔离）─────
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=PAY"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=PICK"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=SHIP"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=FBA_RETURN"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=RELABEL"
curl      "http://localhost:8080/api/orders/state/DEMO-001"
# 无参便捷端点（默认订单 DEMO-001）
curl -X POST "http://localhost:8080/api/orders/state/fba-return"

# ── M4 BFF 聚合查询 ────────────────────────────────────
curl "http://localhost:8080/bff/orders/search?status=SHIPPED&page=1&size=10&warehouseCode=Bruchsal"

# ── M3 路径优化 / M1 报表 ──────────────────────────────
curl -X POST "http://localhost:8080/api/routes/optimize"
# ↑ 不带 body = 内置演示问题（Bruchsal 总仓 → Karlsruhe/Frankfurt/Düsseldorf/Mönchengladbach）
# 带 body = 真实入参（depot + vehicles + deliveries；可选 speedKmh / maxIterations）
curl -X POST "http://localhost:8080/api/routes/optimize" -H 'Content-Type: application/json' -d '{
  "depot":{"id":"Bruchsal-总仓","lat":49.1243,"lon":8.5987},
  "vehicles":[{"id":"V-01","capacity":20},{"id":"V-02","capacity":20}],
  "deliveries":[{"id":"D-01","name":"Karlsruhe","lat":49.0069,"lon":8.4037,"demand":3}]
}'
curl http://localhost:8080/api/reports/dashboard

# ── M5 可观测性（P1-1）───────────────
# 单服务指标（Prometheus 文本格式；含 JVM / HTTP / 业务指标）
curl http://localhost:8081/actuator/prometheus | head -30
# Prometheus 界面（抓取目标 / PromQL 查询）
open http://localhost:9090/targets
# Grafana 看板（演示环境已开匿名只读；admin 密码默认 aslp-admin）
open http://localhost:3000/d/aslp-overview

# ── M5 链路追踪（P1-3）───────────────
# Zipkin 界面：按服务名 / span 名 / 标签查链路；一条链路能看到 gateway → 下游服务的完整传递
open http://localhost:9411/zipkin/
# 用 API 直接看「跨服务链路」（下面这条会列出同时含 gateway 与 order-service 的 trace）
curl -s "http://localhost:9411/api/v2/traces?serviceName=order-service&limit=20"
# 已上报 span 的服务清单
curl -s http://localhost:9411/api/v2/services

# ── M5 日志聚合（P1-1b）───────────────
# Grafana 里看日志（含日志量曲线与 traceId 跳链路的派生字段）
open http://localhost:3000/d/aslp-overview
# 用 Loki API 直接查某个服务的日志（LogQL）
curl -s -G http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service="aslp_order_service"}' --data-urlencode 'limit=20'
# 只看 ERROR 级别（level 标签由 Promtail 的 pipeline stage 抽取）
curl -s -G http://localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service=~"aslp_.+"} | level="ERROR"' --data-urlencode 'limit=20'
# Promtail 采集状况（已读多少条 / 有无解析错误）
curl -s http://localhost:9080/metrics | grep promtail_docker_target_entries_total
```

```bash
# ── M5 外部平台契约（P1-4）─────────────
# 诊断探针：真实 SP-API 客户端单次调用（LWA 换令牌 → /orders/v0/orders 分页 → /orderItems 商品名）
# 无副作用：不落库、不走熔断器，可随时对生产调用
curl -s -X POST http://localhost:8080/api/orders/spapi/probe -H "Authorization: Bearer $TOKEN"
# 用不同 MarketplaceId 命中不同契约桩场景（限流 / 超时 / 5xx / 4xx / 空结果）
curl -s -X POST 'http://localhost:8080/api/orders/spapi/probe?marketplaceId=AMZN-RATE-LIMITED' -H "Authorization: Bearer $TOKEN"
curl -s -X POST 'http://localhost:8080/api/orders/spapi/probe?marketplaceId=AMZN-TIMEOUT' -H "Authorization: Bearer $TOKEN"

# WireMock 管理端点：看桩映射是否加载 / 精确统计「平台被调了几次」（验证分页真的翻了两页）
curl -s http://localhost:8099/__admin/mappings | head -c 200
curl -s -X POST http://localhost:8099/__admin/requests/count \
  -H 'Content-Type: application/json' -d '{"method":"GET","urlPath":"/orders/v0/orders"}'
```

```bash
# ── M5 邮件（P1-5：MailHog + Thymeleaf）────────
# Web 收件箱（直接看 HTML 渲染效果）：http://localhost:8025
# 手动触发一轮低库存巡检并补货邮件（force=true 绕过节流；网关已限 ADMIN）
curl -s -X POST http://localhost:8080/api/inventory/warnings/trigger -H "Authorization: Bearer $TOKEN"
# 预警状态：阈值 / 低库存明细 / 还要等多久才能再发信（回答「邮件为什么没来」）
curl -s http://localhost:8080/api/inventory/warnings/status -H "Authorization: Bearer $TOKEN"
# 邮件内容用 REST API 看（原始 MIME 需解码，主题/正文都是 quoted-printable）
curl -s http://localhost:8025/api/v2/messages | head -c 300

# ── M5 单据对象存储（P1-6：MinIO + PDF）────────
# 生成面单（可重复调用：每次一个新版本，历史留痕）与报关单
curl -s -X POST http://localhost:8080/api/orders/AMZ-1001/documents/shipping-label -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost:8080/api/orders/AMZ-1001/documents/customs-declaration -H "Authorization: Bearer $TOKEN"
# 下载最新一版（application/pdf，可直接打印）与列举全部版本
curl -s -o /tmp/label.pdf http://localhost:8080/api/orders/AMZ-1001/documents/shipping-label -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/orders/AMZ-1001/documents -H "Authorization: Bearer $TOKEN"
# MinIO 控制台（演示凭据 aslp-minio-admin / aslp-minio-secret）：http://localhost:9001
# 对象存储健康（桶是否可达）：curl -s http://localhost:8081/actuator/health | grep minio
```

---

## 5. 项目结构

```
smart-logistics-platform/
├── pom.xml                        # 聚合 POM（groupId=com.aslp，Java 21 / Boot 3.3 / Cloud 2023.0.3）
├── Dockerfile                     # 统一多模块构建（ARG MODULE 选择服务，依赖层跨镜像复用）
├── .dockerignore                  # 排除 target/.git/.gradle，加速构建
├── docker-compose.yml             # PG16 / Redis7 / ZooKeeper+Kafka7.6 / 6 个 Java 服务
├── .vscode/settings.json          # 关闭 JDT 自动构建，避免与 Maven 抢占 target/classes
├── scripts/
│   ├── smoke-test.sh              # 端到端冒烟测试 96 项断言（支持 --external 打外部服务）
│   └── container-verify.sh        # P0-3 容器全量验证：构建 → 启动 → healthy → 断言
├── wiremock/mappings/*.json       # P1-4：Amazon SP-API 契约桩（11 个：LWA 换令牌 / 分页 / 限流 / 超时 / 5xx / 4xx / 空结果 / 商品明细）
├── monitoring/                    # P1-1 / P1-1b / P1-3（全部以文件版本化，容器启动即加载）
│   ├── prometheus/prometheus.yml  # 抓取配置（6 个服务的 /actuator/prometheus）
│   ├── loki/loki.yml              # 日志存储（单机、tsdb + 文件系统）
│   ├── promtail/promtail.yml      # 日志采集（Docker API + 只保留低基数标签）
│   └── grafana/
│       ├── provisioning/          # 数据源（Prometheus / Loki，含日志→Zipkin 派生字段）与看板加载器
│       └── dashboards/*.json      # ASLP 总览看板（11 个面板，含日志面板）
├── test-data/mock-test-data.json  # Amazon/eBay 订单、库存预警、VRP、DHL/DPD 轨迹
├── gateway/                       # Spring Cloud Gateway + BFF + 安全配置
│   └── src/main/java/com/aslp/gateway/
│       ├── GatewayApplication.java
│       ├── bff/{BffOrderController,OrderQueryRequest}.java     # M4 BFF
│       └── security/VpnSecurityConfig.java                     # M4 VPN JWT + 角色映射
└── services/
    ├── auth-service/              # M1 认证：SecurityConfig + JwtTokenService + AuthController
    ├── inventory-service/         # M2 库存：entity/repository/service(锁)/dto(查询响应)/controller/task(预警+邮件)
    │   └── src/main/resources/db/migration/inventory/          # P0-4：V1 建表 + V2 种子数据
    ├── order-service/             # M1/M4：entity(OrderRecord) + repository + service(幂等落库)
    │   │                          #        + statemachine(按订单隔离) + strategy(策略模式)
    │   │                          #        + spapi(SP-API 客户端/令牌/映射) + document(单据 PDF) + config + task
    │   └── src/main/resources/db/migration/order/              # P0-4：V1 建表
    ├── report-service/            # M1 报表：ECharts 数据源
    └── route-service/             # M3 路由：engine(运费规则 + VRP 成本模型/问题装配) + service(VRP 求解/追踪) + dto(求解入参/出参)
```

> **Java 包根统一为 `com.aslp.*`**，不含任何具体主体标识（详见 §12 命名与脱敏约定）。
> **数据库架构由 Flyway 版本化管理**（P0-4），`ddl-auto` 已切换为 `validate`，
> 两个服务各用独立历史表（`flyway_schema_history_order` / `_inventory`）共用同一物理库。
>
> 逐文件职责、配置读法与建议阅读顺序见 **§13 新人阅读指引（代码地图）**。

---

## 6. 关键技术实现

| 模式 / 能力 | 实现位置 | 要点 |
|---|---|---|
| **策略模式** | `order-service` `OrderPullStrategy` → `MockAmazonStrategy` / `AmazonSpApiStrategy` | 通过 `aslp.order.mock-enabled` 切换；无真实 SP-API 账号时走 Mock，保证流水线可测。**P1-4 起 `AmazonSpApiStrategy` 已是真实 HTTP 实现**（不再是骨架） |
| **订单幂等落库** | `OrderPullService` + `OrderRecord` | 以平台单号 `orderId` 为唯一键，重复拉取只更新不新增；整体 `@Transactional`，避免部分入库 |
| **仓库编码归一化** | `OrderPullService.WAREHOUSE_ALIASES` | 兼容 API 返回的 `Moenchengladbach` 无变音符写法，统一为 `Mönchengladbach` |
| **异常打标** | `OrderRecord.errorTag` | `ADDRESS_INVALID` / `POSTCODE_MISSING` / `MATCH_FAILED`；客服修正接口清除标签后重新进入流水线 |
| **状态机** | `SimpleOrderStateMachine`（纯 Java）+ `OrderStateMachineService` | CREATED→PAID→PICKED→SHIPPED→**FBA_RETURN_LABEL**→FBA_RELABELED→DELIVERED→COMPLETED；非法转换被拒绝；**状态按订单号隔离** |
| **分布式锁** | `inventory-service` `InventoryLockService` | Redisson `RLock` key=`inventory:lock:{sku}:{warehouse}`，等待 2s / 持有 10s 防死锁；配合 `@Version` 乐观锁 |
| **乐观锁** | `InventoryItem.version` | JPA `@Version`，防并发覆盖（种子数据必须写入 `version = 0`） |
| **规则引擎** | `route-service` `FreightRule` → `EuropeDhlRule` | 基础费 + 距离×0.12 + 重量×0.35，DE 区基础费 5.0 / 其他 8.0 |
| **VRP 优化** | `route-service` `VrpRouteService` + `VrpProblemFactory`（jsprit 1.8） | 用官方高层入口 `Jsprit.Builder` 装配算法（**不能裸 `new SearchStrategyManager()`**，那是空策略注册表，见 §9 #27）；问题侧绑定 `HaversineCostModel`（**真实 km**，不用 jsprit 默认的「坐标单位欧氏距离」）；固定随机种子保证结果可复现；出参 `VrpPlan` 给出逐车路线、停靠顺序、里程、载重、未指派作业；无解时返回 200 + `feasible:false` 而非 500 |
| **BFF 聚合** | `BffOrderController` | 多条件分页 + `@NotBlank`/`@Min` 校验参数对象；筛选项按需回显（不传的键不出现） |
| **网关限流（P1-2）** | `gateway` `RateLimitConfig` + `RequestRateLimiter` | Redis 令牌桶（`RedisRateLimiter`）。**按需挂路由，不用 default-filters 全局限流**：`POST /api/orders/pull` 用户维度 1 次/秒（对应 M1「平台 API 严格限流」）、`/api/auth/**` IP 维度 10 次/秒（登录前无用户身份）。key 带 `user:` / `ip:` 前缀—— **SCG 的令牌桶 key 不含 routeId**（`getKeys(String)` 只收解析器输出），不隔离就会与其它路由共用桶；匿名回落 IP，**绝不返回空 key**（空 key 会被框架 403）。超限返回 **429** |
| **容错与降级（P1-2）** | `order-service` `PlatformPullGateway` | Resilience4j 注解叠加顺序 **Retry -> CircuitBreaker -> Bulkhead**；回退方法挂**最外层 Retry** 上（挂到 CB 上会被内层吞掉异常，导致重试永不触发）；任何失败合成 `degraded=true` 结果 → 接口返回 **200 降级响应而非 500**；熔断状态可经 `/actuator/circuitbreakers` 查证 |
| **故障演练（P1-2）** | `PlatformFailureSwitch` + `POST /api/orders/mock/failure-mode` | 可控故障注入（仅 Mock 策略读取，网关 docker 链路限 ADMIN），用于端到端证明「连续失败 -> 熔断打开 -> 降级响应 -> 自动恢复」 |
| **VPN 安全接入** | `VpnSecurityConfig` | WebFlux `ServerHttpSecurity`；dev 放行 / docker 强制 JWT + 角色（`/bff/orders/search` 需 `USER`，`POST /api/inventory/**` 需 `ADMIN`） |
| **可观测性（P1-1）** | 每服务 `micrometer-registry-prometheus` + `management.metrics.tags.application` | 指标统一带 `application` 标签（Prometheus 侧可直接按服务聚合）；`/actuator/prometheus` 暴露抓取端点；`percentiles-histogram` 对 `http.server.requests` / `spring.cloud.gateway.requests` / `aslp.vrp.solve` 开启，使 P95 可算。见 §7 与 §9 #30/#31 |
| **业务指标（P1-1）** | `OrderPullService` / `VrpRouteService` / `InventoryLockService` | 不只暴露 JVM/HTTP：订单拉取结局（success/failed/degraded × 平台）、VRP 求解耗时与里程、库存锁操作结果（applied / rejected_balance / rejected_lock / rejected_invalid_qty）—— 每个标签都能对应一个处置动作 |
| **监控栈（P1-1）** | `monitoring/` + compose 的 `aslp_prometheus` / `aslp_grafana` | 抓取配置、数据源、看板均以文件版本化（不靠手工点击）；看板 uid 固定为 `aslp-overview`，Prometheus 保留 7 天 |
| **业务追踪 span（P1-3）** | `OrderPullService` / `VrpRouteService` 的 `Observation` | 一份埋点同时产出 **span**（高基数属性，如 `stopCount`/`totalDistanceKm`/`created`）与 **指标**（低基数标签，如 `feasible`/`platform`/`outcome`）；**不要再手写同名 Timer**，否则计数翻倍。`openScope()` 把 traceId 放进 MDC，日志里能直接搜到 |
| **链路追踪栈（P1-3）** | `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` + `aslp_zipkin` | Boot 3 默认 W3C `traceparent` 传播，网关（WebFlux）与 Servlet 下游可互通；采样率 `management.tracing.sampling.probability` 演示为 `1.0`（生产需下调）；上报地址用**连字符别名** `aslp-zipkin`（见 §9 #35） |
| **日志聚合（P1-1b）** | `monitoring/promtail/promtail.yml` + `monitoring/loki/loki.yml` + `aslp_promtail` / `aslp_loki` | Promtail 用 **Docker API** 而不是 tail 日志文件（macOS 上 `/var/lib/docker` 在虚拟机里，挂了也是空目录）；只保留低基数标签 `container/service/project/level`，**traceId 不做成标签**（会让索引基数爆炸）而是交给 Grafana 的 `derivedFields` 在查询时抽取并生成跳 Zipkin 的链接 |
| **邮件模板化（P1-5）** | `inventory-service` `ReplenishmentMailService` + `templates/email/replenishment.html` | Thymeleaf 渲染 HTML，并用 `MimeMessageHelper.setText(plain, html)` 同时挂 **multipart/alternative** 两份正文（部分网关只认 text/plain，没兑底会收到空邮件）。模板样式全部**行内化**、布局用 `<table>`：邮件客户端对 `<style>` 与现代 CSS 支持极差。**渲染在 try 之外**：模板写错是代码缺陷，不能当成 SMTP 故障被默默吞掉 |
| **邮件节流（P1-5）** | `InventoryWarningTask` + `aslp.inventory.warning.mail-interval` | 扫描周期（60s）与发信周期（默认 30m）是两个概念。旧实现「扫一次发 N 封」，在 SMTP 不可用时被「发送失败」掩盖；邮件真通了就会变成**邮件轰炸**。手动触发端点默认 `force=true` 绕过节流（人工动作应当立即生效）；**发信失败不计入节流窗口**（下一轮立即重试） |
| **单据 PDF 生成（P1-6）** | `order-service` `document/PdfDocumentWriter`（OpenPDF） | 面单（仓内作业联）与报关单（CN22 摘要）两类。选 **OpenPDF（LGPL）而不是 iText 7（AGPL）**：随货单据会被货代/海关接触，AGPL 会带来不必要的许可证义务。**正文只用 ASCII（英文/德文）**：PDF 内置 Base14 字体没有中文字形，写中文会变空白方块（要中文需内嵌 CJK 字体子集，属后续项）。压缩级别设为 0：牺牲几十 KB 体积，换来「排障时能直接在文件里搜单号」 |
| **对象存储（P1-6）** | `service/DocumentStorageService` + `config/MinioConfig` | 对象键 `orders/{orderId}/{kind}-{UTC时间戳}.pdf`：按订单分组（可整体归档/GDPR 删除）、带时间戳（重打留痕，「最新一版」= 键最大者，ISO-8601 的字典序即时间序）；sha256 随对象存元数据（单据是对外凭证，事后要能校验）；**两个客户端** —— 上传/下载走内网端点，预签名走对外端点（签名覆盖 Host，必须用同一个 Host 签） |
| **失败语义（P1-6）** | `DocumentNotFoundException` / `DocumentStorageException` | 分开两类：订单不存在或还没生成过 → **404**（正常业务状态，前端可引导用户点「生成」）；对象存储不可用 → **503**（依赖故障，可重试）。一律 200 + success=false 会让前端无法区分这两种情況 |
| **外部平台客户端（P1-4）** | `order-service` `spapi/SpApiOrderClient` + `LwaTokenClient` + `SpApiOrderMapper` | 三步真实契约：`POST {token-url}`（LWA 表单换 access_token，进程内缓存 + 提前 60s 失效）→ `GET /orders/v0/orders`（`NextToken` 分页 + `max-pages` 防不收敛）→ `GET /orders/v0/orders/{id}/orderItems`（补商品名，失败不影响主流程）。`CreatedAfter` 为 ISO-8601 UTC；鉴权头 `x-amz-access-token`。**显式 connect/read 超时**：默认的「无限等待」会让平台僵死时线程被拖住，熔断器救不了被占住的线程 |
| **失败分类（P1-4 × P1-2）** | `PlatformUnavailableException` / `RateLimitedException` / `SpApiClientException` | **重试语义就写在这条继承线上**：429（子类，带 `Retry-After`）/ 5xx / 超时 → `PlatformUnavailableException` 家族，命中 `retry-exceptions` → 交给 P1-2 重试熔断降级；401 → 刷一次令牌再试；其他 4xx → `SpApiClientException`（**不在可重试家族里**，避免重试打光配额）。分错这一刀，要么该重试的不重试，要么不该重试的狂重试 |
| **契约测试（P1-4）** | `SpApiOrderClientContractTest` + `SpApiProbeControllerTest` + `SpApiRetryClassificationTest` | 用 WireMock 起在**随机端口**上做真实 HTTP（不是 Mockito），断言路径/查询参数/鉴权头与「状态码 → 异常类」映射；再用真实 `RetryConfig.getExceptionPredicate()` 锁死「429 子类命中重试配置」——避免 spapi 包与 yml 两处各自漂移 |
| **契约桩容器（P1-4）** | `wiremock/mappings/*.json` + compose 的 `aslp_wiremock` | 桩以**文件**版本化（跟 `monitoring/` 同一思路：契约是要进代码评审的资产）；靠 `MarketplaceIds` 查询参数分流，于是「正常/限流/超时/5xx/4xx/空结果」共用同一个端点，端到端断言无需为每种故障重启容器 |
| **诊断探针（P1-4）** | `POST /api/orders/spapi/probe` | **无副作用**：只跑客户端 + 映射器，不落库、不写指标、不经熔断器 → 可随时体检「外部契约是否还通」，且不会因为探针自身失败把熔断器推向打开 |
| **JWT 签发** | `auth-service` `JwtTokenService` | JJWT 0.12.5 HS384，含 `roles` / `scope` 声明，TTL 可配 || **定时任务** | `OrderSyncTask`（5 分钟，容器启用）、`InventoryWarningTask`（60s）、`DatabaseBackupTask`（每日 02:00） | 订单流水线式自动导入；安全库存预警 + 补货邮件；`pg_dump` 参数化并可开关 |

---

## 7. 配置说明（务必对齐）

| 配置项 | 本地（默认 profile） | 容器（`SPRING_PROFILES_ACTIVE=docker`） |
|---|---|---|
| PostgreSQL | `localhost:5432/aslp`，`aslp` / `aslp123` | `aslp_postgres:5432/aslp`，同上 |
| Redis 连接 | `spring.data.redis.host=localhost` / `port=6379` | `spring.data.redis.host=aslp_redis` / `port=6379`（**必须**写 `spring.data.redis.*`；`redisson.singleServerConfig.*` 不被 starter 绑定，会被静默忽略并回落 localhost） |
| 网关下游 URI | `http://localhost:8081..8085` | `http://aslp-order-service:8081` 等**连字符网络别名**（下划线主机名非法，见 §9 #21） |
| 网关限流令牌桶（P1-2） | `spring.data.redis=localhost:6379`；`/api/orders/pull` 1 次/秒（用户维度）/ `/api/auth/**` 10 次/秒（IP 维度） | 同上但 Redis 指向 `aslp_redis`；不走 `default-filters` 全局限流（见 §9 #26） |
| 平台调用容错（P1-2） | `resilience4j.*`：重试 3 次（指数退避 200ms×2）/ 熔断 50% 阈值、窗口 5、最少 3 次调用 / 舱壁 4 并发 | 同上（两个 profile 配置一致，仅注释差异） |
| Schema 管理 | Flyway（`db/migration/{order,inventory}`），`ddl-auto=validate` | 同上 |
| Flyway 历史表 | `flyway_schema_history_order` / `flyway_schema_history_inventory` | 同上 |
| JWT 密钥 | `aslp.jwt.secret`（auth-service 签发用） | `ASLP_JWT_SECRET` 注入，**gateway 与 auth-service 必须一致** |
| 订单拉取模式 | `aslp.order.mock-enabled=true`（Mock 策略） | 同上；接真实凭据时改 `false` |
| 订单定时同步 | `aslp.order.sync.enabled=false`（本地关闭） | `enabled=true`，cron `0 */5 * * * *` |
| 数据库备份 | `aslp.backup.enabled=false` | `enabled=true`，host=`aslp_postgres` |
| 邮件（P1-5） | `spring.mail.host=localhost:1025`（需 `docker compose up -d aslp_mailhog`）；`management.health.mail.enabled=false`（开发机没起 SMTP 时不该把自己判 DOWN） | host=`aslp-mailhog:1025`；**健康探测开启**（容器里真有 SMTP，发不出去必须能被看见）；收件人/阈值/发信间隔由 `aslp.inventory.warning.*` 控制（邮件节流 `mail-interval=30m`） |
| 对象存储（P1-6） | `aslp.minio.*`：endpoint=`http://localhost:9000`（compose 映射），`region=us-east-1`，桶 `aslp-documents`，预签名 15m | endpoint=`http://aslp-minio:9000`（服务别名）+ **`public-endpoint=http://localhost:9000`**：预签名 URL 必须用对外端点签（签名覆盖 Host，用内网端点签出来的浏览器打不开）；`spring.datasource` 之外的依赖均就绪后 `order-service` 才启动（compose `depends_on: service_healthy`） |
| 单据抬头（P1-6） | `aslp.document.*`：发货方名称/地址/税号、目的国、申报价值、HS 编码、币种 | 同左（演示值，非真实主体）。**改抬头/调申报价值不需要重新构建镜像** |
| 邮件健康探测 | 见上行「邮件（P1-5）」 | docker profile 已恢复开启（`aslp_mailhog` 存在） |
| 指标暴露与抓取（P1-1） | `management.endpoints.web.exposure.include` 含 `prometheus,metrics`；`management.metrics.tags.application=${spring.application.name}` | **docker profile 必须同时写**：profile 里的 `include` 会整体覆盖基础 profile，漏写就抓不到（已踩过）。Prometheus 抓取目标用连字符别名（下划线主机名会让 Tomcat 返回 400，见 §9 #30） |
| 监控栈（P1-1） | `aslp_prometheus:9090`（保留 7 天）/ `aslp_grafana:3000`（匿名只读） | Grafana 管理密码经 `GF_SECURITY_ADMIN_PASSWORD` 注入（默认 `aslp-admin`，仅演示） |
| 链路追踪（P1-3） | `management.tracing.sampling.probability=1.0`（全量采样）；`management.zipkin.tracing.endpoint=http://localhost:9411/api/v2/spans` | docker profile 必须把 endpoint 改指向 `http://aslp-zipkin:9411/api/v2/spans`：**下划线主机名会让 java.net.URI 解析不出 host**，WebFlux 网关上报时报 `Host is not specified`（见 §9 #35）。采样率 1.0 只适合演示，生产按流量调到 0.1 级别 |
| 日志采集（P1-1b） | 无（业务代码不感知）；Promtail 侧 `-config.file` 与 `docker_sd_configs.host=unix:///var/run/docker.sock` | Loki 侧 `limits_config.ingestion_rate_mb` 演示环境放宽到 16MB/s（避免丢日志）。新增服务的日志会自动被采集（按容器名前缀 `aslp_` 过滤），无需改配置 |
| 平台接入（P1-4） | `aslp.order.amazon.*`：真实端点 `sellingpartnerapi-eu.amazon.com` + `api.amazon.com/auth/o2/token`，凭据从环境变量注入（`AMAZON_LWA_CLIENT_ID` / `_CLIENT_SECRET` / `_REFRESH_TOKEN`，**不写进仓库**）；`read-timeout=5s`、`max-pages=5` | 同一套配置但 endpoint/token-url 指向 `http://aslp-wiremock:8080`（桩用的假凭据，非密钥）、`read-timeout=1s`（**故意短于超时桩的 3s 延迟**，用来证明读超时真的生效） |
| 契约桩（P1-4） | 无（本地跑单测时用进程内 WireMock，不需要容器） | `aslp_wiremock`（`:8099` 映射到容器 8080）；桩映射只读挂载 `./wiremock:/home/wiremock:ro`；`order-service` 经连字符别名 `aslp-wiremock` 寻址（下划线陷阱见 §9 #21/#30/#35） |
| 仓库映射（P1-4） | `aslp.order.amazon.default-warehouse=Bruchsal` + `warehouse-by-city`（城市→履约仓，大小写无关） | 同左。**口径放在配置里而不是代码里**：开新仓不用改代码；未命中城市落到默认仓（不留空仓库，否则拣货任务无从下手） |

> 生产部署前必须通过 `ASLP_JWT_SECRET` 注入强随机密钥，并替换 compose 中的 `POSTGRES_PASSWORD`。

---

## 8. 验证清单（2026-09-17 实测）

- [x] `mvn clean package -T 1C` — **BUILD SUCCESS**，6 个模块全部产出可执行 fat jar
- [x] 单元测试 **178 个全部通过（0 跳过、0 失败）**：gateway 9 / order 78 / inventory 36 / route 49 / auth 5 / report 1
- [x] `bash scripts/container-verify.sh`（先 `docker compose down -v` 冷启动）— **EXIT=0**：构建 6 个服务镜像 → 启动 → **18/18 容器就绪**（含 Prometheus / Grafana / Zipkin / Loki / Promtail / WireMock 桩 / MailHog / MinIO）→ 端到端断言 **96/96 通过**
- [x] **VRP 引擎（P1-2b）**：求解演示问题得 1 条路线 / 4 个停靠点 / 615.1 km；单作业往返 Bruchsal→Karlsruhe 精确等于 **38.6 km**（几何锁定，同时证明单位是 km 而非「度」）；运力不足返回 200 + `feasible:false` + 未指派作业列表；缺 `deliveries` 返回 400
- [x] **指标可观测性（P1-1）**：`/actuator/prometheus` 输出约 200KB 指标（含 `application` 标签与业务指标）；Prometheus **7/7 抓取目标 healthy**（6 业务服务 + 自身）；`aslp_vrp_distance_count` 等业务指标可从 TSDB 查到；Grafana `database ok` 且看板 `aslp-overview` 已自动加载；P95 经 `histogram_quantile` 实测可得（如 route-service 0.063s）
- [x] **链路追踪（P1-3）**：6 个服务均上报 span（含 WebFlux 网关）；**跨服务同一 traceId 实测 23 条**（同一条 trace 里同时含 gateway 与 order-service 的 span）；业务 span `aslp.vrp.solve` 带 `stopCount` / `totalDistanceKm` 等业务标签；日志已带 `[traceId-spanId]`（MDC 生效）；且**未出现指标重复计时**（一次拉取 → `aslp_order_pull_seconds_count` = 1）
- [x] **日志聚合（P1-1b）**：Promtail 累计读取容器日志 **2000+ 条、解析错误 0**；Loki 可查到 `{project="aslp"}` 的日志流；采样 80~93 行日志中均带 traceId（可一键跳 Zipkin）；Grafana Loki 数据源连通（uid=aslp-loki）
- [x] **平台契约（P1-4）**：容器内探针实测 `pages=2 / count=3`（**WireMock 请求计数确认订单列表接口恰好被调 2 次** = 真正翻了页）；商品名来自 `/orderItems`；城市 → 履约仓映射正确；缺收货城市的订单自动打 `ADDRESS_INVALID`；明细接口 500 时商品名回退占位符；**429 → `RateLimitedException`（retryAfter=7）/ 503 → `PlatformUnavailableException` / 读超时 → `elapsedMs=1005`（= 配置的 1s，没等到桩的 3s）/ 400 → `SpApiClientException`（retryable=false）**；空结果 `ok=true,count=0` 不判失败
- [x] **邮件真实化（P1-5）**：`POST /api/inventory/warnings/trigger` 实测 `mailSent=true`（阈值 10）；MailHog 收件箱收到邮件，**解码后**主题=`[库存补货建议] AMZ-9999@Mönchengladbach 剩余 5（阈值 10）`，HTML 正文 5591 字符（含 `<table>` 行内样式表格）、建议补货量 15 与 SKU/仓库均正确；邮件为 `multipart/alternative`（纯文本兜底 + HTML）；`force=false` 再触发返回 `mailSkipped=true`（**邮件节流在容器里真的生效**）；`/actuator/health` 的 `mail` 组件为 UP
- [x] **单据对象存储（P1-6）**：`POST .../documents/shipping-label` 返回对象键 `orders/AMZ-1001/shipping-label-<UTC>.pdf` + 64 位 sha256；**下载得到 3771 字节的合法 PDF**（落盘于 `target/smoke-logs/p1-6-label-e2e.pdf` 可人工打开）；报关单走不同对象键；列举可见多个版本（重打留痕）；**预签名 URL 用对外端点签，宿主直接下载成功**；白名单外的类型返回 400；`/actuator/health` 的 `minio` 组件 UP 且 details 带桶名与失败原因
- [x] Flyway 迁移：`flyway_schema_history_order` / `flyway_schema_history_inventory` + `orders` / `inventory` 四表均由迁移脚本创建，`ddl-auto=validate` 校验通过
- [x] 订单幂等导入：重复 `POST /api/orders/pull` 只更新不新增
- [x] 状态机按订单号隔离：A 订单推进不影响 B 订单
- [x] 匿名访问 `/bff/orders/search` 返回 401、携带 JWT 返回 200（docker profile 鉴权生效）
- [x] `docker compose config` — 全部服务配置有效

---

## 9. 已修复的阻塞性问题（累计 47 项）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | 网关无法访问（全站 401） | `VpnSecurityConfig` 注入 Servlet 的 `HttpSecurity` 且缺 `@Bean`，在 WebFlux 网关中从未生效 | 改用 `ServerHttpSecurity` + `@EnableWebFluxSecurity` + `@Bean`，按 profile 分 dev/docker 两套策略 |
| 2 | 网关启动即失败 | Spring Cloud 2023.0.0 仅兼容 Boot 3.2.x | 升级 `spring-cloud.version` → **2023.0.3** |
| 3 | 所有模块测试无法编译 | 6 个模块均缺 `spring-boot-starter-test` | 补齐测试依赖（含 `spring-security-test`、H2） |
| 4 | `java -jar` 报 no main manifest | 根 POM 仅在 `pluginManagement` 声明 `spring-boot-maven-plugin`，未在模块内激活 | 6 个模块 `<build><plugins>` 显式声明 |
| 5 | `order-service` 依赖解析失败 | `spring-statemachine-core:1.2.14` 不存在于 Maven Central（代码早已改用纯 Java 状态机） | 删除该残留依赖 |
| 6 | `inventory-service` 编译失败 | 使用 `JavaMailSender` 但缺 `spring-boot-starter-mail` | 补齐 mail 依赖 |
| 7 | 库存预警 JPA 启动失败 | `findByQuantityLessThan` 与实体字段 `availableQty` 不匹配 | 改名 `findByAvailableQtyLessThan`，并接入补货邮件 |
| 8 | 网关路由全 503 | 使用 `lb://service-name` 但项目无注册中心、无 loadbalancer；docker profile 还写成 `lb://aslp_x:8081`（非法） | 改为直连 `http://host:port`，并补 `spring-cloud-starter-loadbalancer` |
| 9 | 数据库三方配置不一致 | compose=`aslp`，inventory=`smart_logistics`+`postgres`，order 用 `localhost` | 全面统一为 `aslp` / `aslp123`，并拆 local/docker 双 profile |
| 10 | `auth-service` 全站 Basic 认证 | 只声明了 `PasswordEncoder`，无 `SecurityFilterChain` | 新增安全链放行 `/api/auth/**`，并实现真实 JWT 签发 |
| 11 | Docker 构建必然失败 | `services/*/Dockerfile` 上下文为子目录，`COPY ../../pom.xml` 属越界；auth/report 直接 COPY 不存在的 `target/*.jar` | 统一为根 `Dockerfile` + `ARG MODULE` |
| 12 | **Maven 构建随机失败：主类编译成功但 surefire 报 `NoClassDefFoundError`** | VS Code Java 语言服务器（JDT LS）以 `target/classes` 为输出目录并自动重建，与 Maven 抢占同一目录 | `.vscode/settings.json` 设 `java.autobuild.enabled=false`；同时关闭 maven-shared-incremental 误删 |
| 13 | `inventory-service` 健康检查 503 且日志刷屏 | 默认 `MailHealthIndicator` 每次探测 SMTP，无邮件服务即判 DOWN | `management.health.mail.enabled=false`，补货邮件改为尽力而为 |
| 14 | 网关路由与 Controller 路径不匹配 | 网关断言 `/api/routes/**`，controller 却是 `/api/route` | controller 映射 `{"/api/routes","/api/route"}` 兼容 |
| 15 | **种子数据导致库存扣减必失败** | `01-schema.sql` 未给 `version` 赋值（NULL），Hibernate `@Version` 生成 `where version = null`，永远 0 行更新 → `StaleStateException` | 种子数据显式写入 `version = 0`，并在脚本内注释说明 |
| 16 | 订单状态机状态被跨订单污染 | `OrderStateMachineService` 持有单个状态机实例，所有订单共享状态 | 改为 `ConcurrentHashMap<orderId, StateMachine>` **按订单号隔离**，并补 4 个单元测试锁定行为 |
| 17 | 订单拉取接口不存在 / 404 | M1「统一 DTO 持久化至 PostgreSQL」只写了策略与 DTO，没有落库链路 | 新增 `OrderRecord` 实体 + `OrderRepository` + `OrderPullService`（幂等落库 + 异常打标）+ `OrderPullController` |
| 18 | **docker profile 下 VPN 鉴权形同虚设** | 网关用 `withJwkSetUri` 校验，但 auth-service 只签发 HS384 对称令牌、**没有 JWKS 端点**，公钥永远拉不到；且默认转换器只识别 `scope`，`hasRole("USER")` 永不匹配（403） | 改为与 auth-service 共享 HS384 密钥解码（`ASLP_JWT_SECRET`），并新增 `roles` → `ROLE_*` 权限映射；RS256+JWKS 保留为 P2-5 |
| 19 | **`.gitignore` 的 `*.sql` 会吞掉所有数据库脚本** | 规则本意是忽略 `pg_dump` 转储，但 `*.sql` 无差别匹配 —— 导致原 `01-schema.sql` 从未被提交，Flyway 迁移脚本也会同样丢失 | 改为精确匹配 `*.dump` / `*.dump.sql` / `**/aslp_backup_*.sql`，并加注释说明原因 |
| 20 | 目录名拼写错误 `smart-logistocs-platform` | 建项目时手滑（`logistocs`） | 重命名为 `smart-logistics-platform`，同步 Maven `artifactId` 与文档引用（git 识别为纯重命名） |
| 21 | **容器内网关启动即失败并反复重启**：`URISyntaxException: Expected scheme-specific part at index 5: http:` | 服务名/容器名 `aslp_order_service` 含下划线，而 RFC 2396 的 `hostname` 不允许 `_` → `java.net.URI` 把 authority 解析为 registry-based（实测 `getHost()==null`、`getPort()==-1`）→ 网关 `Route.AbstractBuilder.uri(URI)` 命中「http 且无显式端口」分支，用 `UriComponentsBuilder.fromUri(...).port(80).build(true).toUri()` 重建 URI，authority 丢失后生成非法串 `http:` | 为 5 个被网关路由的服务声明**连字符网络别名**（`aslp-order-service` 等），路由 URI 改用别名；`container_name` 保留 `aslp_*` 约定。JDBC / Kafka / Redisson 用各自宽松解析器，实测无需改动 |
| 22 | **容器内 inventory 连不上 Redis**：Redisson 报 `Connection refused: localhost/127.0.0.1:6379` | `redisson.singleServerConfig.address` **不会被绑定**：`redisson-spring-boot-starter` 只通过 `@EnableConfigurationProperties` 绑定 `spring.data.redis.*`（`RedisProperties`）与 `spring.redis.redisson.{config,file}`（`RedissonProperties`）→ 属性被静默忽略、回落默认 `localhost:6379`；本地开发因 compose 映射了 6379 端口而“碰巧”可用 | 两个 profile 均改用 `spring.data.redis.host/port`（docker = `aslp_redis`，本地 = `localhost`），删除死配置。原 `connectionMinimumIdleSize: 2` 同样从未生效，本次不启用（保持 starter 默认值） |
| 23 | BFF `GET /bff/orders/search?status=PAID`（**缺 `warehouseCode`**）返回 **HTTP 500** | `BffOrderController.search` 用 `Map.of(...)` 回显可选参数，而 `Map.of` **拒绝 null 值**，缺省筛选项即抛 `NullPointerException` | 改为按需装配 `LinkedHashMap`，仅回显实际传入的筛选项；补回归测试 `bffSearchToleratesOmittedOptionalParameters` |
| 24 | **释放锁定库存可凭空增加可用库存**（P0-5 实现时发现） | 原 `releaseLockedInventory` 不校验「释放量 ≤ 当前锁定量」，用 `Math.max(0, ...)` 掩盖了越界；扣减/释放均未拦负数；释放路径未加分布式锁，与扣减并发时可互相覆盖 | 越界/非正数一律拒绝；释放与扣减共用 `inventory:lock:{sku}:{warehouse}`；方法返回 boolean 供接口透出 `success` |
| 25 | **网关启动失败**（P1-2 实现时引入并修复）：`required a single bean, but 2 were found` | `RateLimitConfig` 注入多个 `KeyResolver`，而 `GatewayAutoConfiguration#requestRateLimiterGatewayFilterFactory` 需要唯一 bean 作默认值 | 主解析器标 `@Primary`（`userKeyResolver`），并保留路由内 SpEL 显式引用的能力 |
| 26 | **全局限流拖垒正常流量**（P1-2 实测发现）：突发后常规请求成片 **429** | SCG 的 `RedisRateLimiter` 令牌桶 key **不含 routeId**（已核对其 4.1.5 字节码：`getKeys(String)` 只收解析器输出）→ 不同阈值的路由会共用令牌桶；且 `default-filters` 全局限流会无差别节流端到端脚本等正常流量 | 改为**按需路由级限流**（仅 `/api/orders/pull` 与 `/api/auth/**`），并为两个维度加 `user:` / `ip:` 前缀隔离令牌桶 |
| 27 | **M3 的 VRP 引擎实际不可用**（单测生成任务发现，**P1-2b 已修复**）：`IllegalStateException: no search-strategy found` | 两层问题叠加：①`VrpRouteService` 用 `new SearchStrategyManager()` 作策略注册表，但**未注册任何搜索策略**，`searchSolutions()` 必抛异常（已用独立程序复现确认）；②即便算法能跑，jsprit 默认的欧氏距离在经纬度上**单位是「度」**（Bruchsal→Karlsruhe = 0.19），而内置 `GreatCircleCosts` 的经纬度顺序又与项目约定**相反**（同一条路线会被算成 11.2 km，真值 19.3 km）。因 `RouteController.optimize()` 当时是桩方法、未调用该服务，端到端一直未暴露 | ①改用官方高层入口 `Jsprit.Builder.buildAlgorithm()`（一次性装配初始解、搜索策略、状态约束与迭代上限）；②新增 `HaversineCostModel` 自行实现 km 级成本（约定 `Location.newInstance(纬度, 经度)`）并配 `GeoDistance` 单测把约定钉死；③`VrpProblemFactory` 集中校验入参（400 而非静默 truncate/回落 0）；④`RouteController.optimize()` 接入真实引擎并支持省略 body（内置演示问题）；⑤新增 25 个单测（含 8 个“旧写法必错”回归证据） |
| 28 | **限流端到端断言时序脆弱**（P1-2b 验证时实测到）：`POST /api/orders/pull` 连续第二次拿到 **200** 而非 429 | 断言写成「串行紧接第二次」，隐含要求「首次 `/pull` 在 1 秒内返回」；容器刚就绪时首次调用含 JIT 与首次访问 DB，耗时可能超过 1 秒 → 令牌桶已按 1/s 补充 → 第二次合法放行，断言假失败 | 改为**并发突发**断言（同瞬间并发 4 次，burst=1 时必然有请求被限），并在注释里写明原因；实测 `429 429 429 200`（1 次拿到令牌、3 次被拒） |
| 29 | 冒烟脚本「期望包含 `["A","B"]`」的断言假失败 | 助手函数用普通 `grep`，而 `[` `]` 在正则里是字符集（bracket expression），JSON 数组内容按字面量永远匹配不上 | 新增的 `check_post_json` 改用 `grep -qF`（固定字符串），并在注释里说明原因 |
| 30 | **Prometheus 抓不到 5 个服务的指标**：`lastError = server returned HTTP status 400`（响应体为空，应用日志也没记录） | 与 #21 同源但**不在同一层**：抓取目标写了容器名 `aslp_order_service`，而 Host 头里的下划线对 RFC 1123 主机名非法，**Tomcat 10.1 在进入应用前就直接返回 400**（网关是 Netty/WebFlux 不校验 Host，所以只有它没报错） | `prometheus.yml` 的 targets 改用 compose 里已声明的连字符别名 `aslp-order-service:8081`（网关仍可用容器名） |
| 31 | 抓取网关报 **401**、抓取 auth-service 报 **403** | 观测端点没进安全链的白名单：网关是 `anyExchange().authenticated()` → 401；auth-service 关了 httpBasic 所以无认证机制可用 → **403 而不是 401**（容易误判成权限配置问题） | 两处安全链均放行 `/actuator/prometheus`，并在代码注释里写明权衡：指标不含业务数据，但生产应改用独立 management 端口 + 来源限制 |
| 32 | **大响应体的断言恒定假失败**：`/actuator/prometheus`（200KB）明明含目标指标，断言却报「未暴露」 | `set -o pipefail` + `grep -q`：**grep 命中即退出**，写端（echo，200KB）收到 SIGPIPE，管道整体返回 **141（非零）** → `if` 走进 else 分支。实测：同一字符串 `case` 匹配成功、`echo|grep -qF` 退出码 141，here-string 为 0。小响应体因写端早已写完而不暴露，故具有欺骗性 | 断言统一改为 **here-string**：`grep -q -- "${expect}" <<< "${body}"`（无写端进程，退出码即真实匹配结果）；三个断言助手一并修正 |
| 33 | **冷启动时监控断言失败**（暖机时却通过） | 断言与 Prometheus 抓取周期赛跑：`scrape_interval=15s`，指标刚产生时还没被 scrape，立刻查 TSDB 必然空（与 #28 同源的时序脆弱） | 两项抓取相关断言改为**有界轮询**（最多 ~48s，命中即停），而非一次性断言 |
| 34 | **所有 Java 服务启动失败**：`FileSystemException: /tmp/tomcat.8081.xxx: No space left on device` | Docker 虚拟机根分区 100% 满（58.4G 用满，0 可用）—— 反复构建镜像累积了 66 个悬空镜像 + 14.5GB 构建缓存；错误发生在 Tomcat 建临时目录阶段，看起来像应用配置问题 | 只清理**悬空镜像与构建缓存**（实测腾出 ~15GB），刻意不碰数据卷与网络（本机还跑着其他项目，卷里有 13GB 数据） |
| 35 | **网关上上报 span 失败**：Zipkin 里始终看不到 `gateway`，日志报 `IllegalArgumentException: Host is not specified`（其他 5 个服务都正常） | **下划线主机名的第三次现身，这次在 WebClient + `java.net.URI` 层**：上报地址写成 `http://aslp_zipkin:9411/...`，下划线使 URI 变成 registry-based（`getHost()==null`）；网关是 WebFlux，用 `ZipkinWebClientSender` 构建 URI 后直接抛错，而 Servlet 服务的上报走较宽松的 `URLConnection` → 只有网关掉队 | 给 Zipkin 加连字符网络别名 `aslp-zipkin`，6 个 docker profile 的上报地址全部改别名（与 #21 网关路由、#30 Tomcat Host 头同一套约定） |
| 36 | **日志 traceId 断言假失败**（新写的断言，与 #32 同根）：日志里明明有 traceId 却判失败；早期版本还因固定 `tail 500` 被故障演练的堆栈日志挤出窗口 | 两个原因叠加：①`set -o pipefail` + `grep -q` 提前退出 → `docker logs` 收到 SIGPIPE → 管道返回 141（**#32 的规则没机械执行到底，只在助手函数里改了**）；②固定 tail 行数在日志量大时不稳定 | 改用 `docker logs --since 10m` 存入变量 + here-string 匹配；并把脚本里剩余的 `\| grep -q` 全部扫出来改掉 |
| 37 | **业务 span 断言偶发失败**（P1-1b 验证时实际碰到） | 按 `serviceName` + 固定 `limit` 拉 trace 时，窗口会被**监控抓取自身产生的 trace 淹没** —— Prometheus 每 15s 抓一次 `/actuator/health` 与 `/actuator/prometheus`，每次抓取都是一条新 trace；业务 span 就被挤出窗口（且单次断言无重试，见 #33） | 改用 `?spanName=aslp.vrp.solve` 精确查询（直接命中业务 span），并加上有界重试；跨服务查询的 limit 也提到 200 |
| 38 | **所有 compose 命令直接失败**：`mapping key "networks" already defined at line 257` | 给 Prometheus 加连字符别名时，在服务头部新增了一个 `networks:`（带 aliases）块，而同一服务末尾原本已有 `networks: - aslp_net` —— **YAML 不允许同名键，后者会直接报错**（好在 `docker compose config` 会立即拦住，不会静默丢掉） | 删掉末尾那条（别名块已包含网络归属）；经验：给已有服务加网络别名时，先搜该服务内是否已有 `networks:` 键 |
| 39 | **P1-4 新增的商品名断言假失败**（自己新写的断言，跑第一次就碰到） | 为了让 WireMock 管理端点的「带空格 JSON」好匹配，对响应体做了 `tr -d ' \\n'`，而这行被复用到**探针响应**上 —— 探针是 Jackson 紧凑 JSON，**空格在字符串值里是有意义的**：`AeroSleep 婴儿床 6 件套` 被压成 `AeroSleep婴儿床6件套`，断言永远匹配不上 | 区分两类响应：探针响应用原样 body（字段间本就无空格）；只有 WireMock 管理端点才去空白。并写进脚本注释，避免后人“统一清理空白” |
| 40 | **整套容器验证白跑一轮**：`smoke-test.sh: line 180: syntax error near unexpected token 'do'`（EXIT=2） | 用编辑工具插入新断言段时，把原有 `echo "...外部模式..."` 与下一行 `for entry in ...` **合并成了一行**——`echo` 把 `for` 当成了普通参数，脚本在解析阶段就挂了 | 教训：**改完 shell 脚本先跑 `bash -n` 再执行**（静态解析只需毫秒，能拦住全部这类人工拼接错误）；本次已补上这一步作为后续轮次的固定动作 |
| 41 | **邮件测试断言“看不懂自己发的邮件”**：正文明明是 HTML + 纯文本两份，测试却读到 `text/plain` 且 part 数=1 | 断言写在了**未发送**的 `MimeMessage` 对象上：JavaMail 的 MIME 头与嵌套结构要等到 `writeTo()`（写入报文流）时才最终确定，直接读 `getContentType()` / `getContent()` 会得到误导结果（实测：序列化后的报文里确实是 `multipart/alternative` + `text/html`） | 改为**序列化 → 重新解析 → 再断言**（相当于“以收件人视角”验收），并递归遍历 MIME 树收集正文，不依赖嵌套层数 |
| 42 | **MinIO 元数据断言写错键名**：`args.userMetadata().get("sha256")` 拿到空集 | MinIO SDK 在 `build()` 时已给用户元数据加上 `x-amz-meta-` 前缀（实测 `{x-amz-meta-sha256=[...]}`），访问器里不再是裸键名 | 断言改用 `x-amz-meta-sha256` / `x-amz-meta-order-id`；并用「失败信息里直接打印实际 Map」的写法避免下次再猜 |
| 43 | **order-service 容器反复重启**：`Parameter 0 of constructor in PdfDocumentWriter required a bean of type 'DocumentProperties' that could not be found` | 新建的 `DocumentProperties` 标注了 `@ConfigurationProperties(prefix="aslp.document")`，但**忘了写进 `@EnableConfigurationProperties`** —— 它就只是个普通类，拿不到配置也不报错。关键教训：这类缺失**编译期无错、178 个单测也全绿**（单测都是直接 `new` 出来的），**只有真的启动一次 Spring 上下文才会暴露** | 把 `{MinioProperties.class, DocumentProperties.class}` 一起注册；并把「容器冷启动」当作不可省的验证环节（同类问题见 #44） |
| 44 | **inventory-service 容器反复重启**：`Invalid fixedDelayString value "60s"; NumberFormatException` | `@Scheduled(fixedDelayString = "...:60s")` 里的 **`60s` 是 Boot 的 Duration 简写，而 `@Scheduled` 的 String 形式只认「纯数字毫秒」或 ISO-8601（`PT60S`）** —— 这个能力属于 `@ConfigurationProperties` 绑定器，不属于 `@Scheduled` | 改用 `check-interval-ms: 60000`（并删掉 `WarningProperties.checkInterval`，避免与 `@Scheduled` 形成双真相源）；在注解上写明原因 |
| 45 | **MinIO 报 `InvalidAccessKeyId`**：`The Access Key Id you provided does not exist in our records.`（服务端凭据明明正确，用 `mc` 验证过） | **把 shell 风格的默认值写法当成了 Spring 的**：写的是 `${MINIO_ROOT_USER:-aslp-minio-admin}`，而 Spring 占位符的默认值分隔符是**单个冒号**（`${VAR:default}`）—— 于是默认值被解析成 **`-aslp-minio-admin`（带前导减号）**。`mc` 能用、SDK 不能用，就是这一个字符的差别 | 改为 `${MINIO_ROOT_USER:aslp-minio-admin}`，并在两个 profile 里加了注释；排查手法值得沉淀：**先判断「服务端凭据对不对」（`mc`/控制台）再看「客户端送了什么」** |
| 46 | **上传成功却返回 503**：`预签名 URL 生成失败：Failed to connect to localhost/[0:0:0:0:0:0:0:1]:9000`（日志里明明已经打印「已生成 shipping-label」） | 预签名客户端用的是**对外端点**（`http://localhost:9000`，容器内不可达）；而 SDK 在签名前如果不知道区域，会先发 `GET /{bucket}?location=` 去问服务端 —— 这次查询打到了不可达的对外端点，于是「上传成功」也被预签名失败变成 503 | `MinioProperties.region`（默认 `us-east-1`）+ `MinioClient.builder().region(...)`：显式区域使 SDK 跳过这次查询；顺带把失败原因写进健康详情（`detail` 字段），下次一眼就能定位 |
| 47 | **邮件断言全部假失败**（邮件明明收到了，`total:1`） | 两层编码叠加：①邮件主题/正文是 **quoted-printable** 编码的（中文变 `=E5=BA=93…`），连短 ASCII 也会被 QP 折行拆开（`AMZ-9999` → `AM…` + `Z-9999`）；②MailHog 是 Go 写的，`encoding/json` 默认把 `<` `>` `&` 转义成 `\u003c` 等，所以连 grep `<table` 都不中 | 断言改为**先解码再看**：用标准库 `email` 解析 `Raw.Data`，打印解码后的主题与 HTML 正文，再对其断言（python3 不可用时优雅跳过）；同时记住：`multipart/alternative` 这类 ASCII 且无特殊字符的串才能直接 grep |

---

## 10. 当前限制与后续路线

### 限制
- **注册中心缺失**：网关使用直连 URI（`lb://` 已移除）。引入 Eureka/Nacos 后应改回服务发现 + `lb://`。
- **MinIO 未启用**：镜像源在部分网络环境不可达，`docker-compose.yml` 中默认注释。
- **无日志保留策略**：Loki 单机文件系统存储，未设保留期与容量上限；生产应配 `retention_period` + 对象存储，并按合规要求定保留时长。
- **追踪采样为全量**：`management.tracing.sampling.probability=1.0` 仅适合演示；生产需下调（如 0.1）并换成 Elasticsearch 存储（当前 Zipkin 用内存存储，重启即清）。
- **观测端点未鉴权**：`/actuator/prometheus` 在 docker profile 下放行（网关与 auth-service 均放行），仅靠网络隔离。生产建议改用独立 management 端口 + 来源限制/双向 TLS。
- **Grafana 为匿名只读演示态**：`GF_AUTH_ANONYMOUS_ENABLED=true`，admin 密码为默认值；生产必须关掉匿名并接入统一认证。
- **无邮件服务器**：补货邮件走 `localhost:1025`，失败仅告警不阻塞（已关闭健康探测）。
- **安全演示态**：`auth-service` 未接入用户表，按用户名推导角色；JWT 为对称密钥，网关侧 JWKS 端点为占位。
- **未接入真实平台**：Amazon SP-API 客户端已是**真实 HTTP 实现**（P1-4），但**未用生产卖家账号实战验过**（LWA 正式授权流程、SP-API 的签名/限流配额、真实报文字段都可能有出入）；eBay 仍无实现（只有 Mock）。
- **商品明细是 N+1 调用**：SP-API 订单列表不含商品名，只能逐单查 `/orderItems`（当前串行，单页 50 单就是 50 次调用）。仅串行 + 单次拉取尚可，规模化前应改并行（有界并发）或改为按需懒加载；`aslp.order.amazon.fetch-item-titles=false` 可先关掉。
- **探针无额外鉴权**：`POST /api/orders/spapi/probe` 只靠网关 JWT 保护，且能触发对外调用；生产应加管理员角色限制 + 频率限制（它毕竟是一个“可以打外部平台”的入口）。
- **邮件仅到“收得下”**：MailHog 是**开发/演示用**收件箱（不转发、无 TLS、镜像仅 amd64，Apple Silicon 上走模拟）；生产需接真实 SMTP/邮件服务（如 SES/Postmark）并配 SPF/DKIM。收件人目前是**全局单值**配置（真实业务需按仓/品类路由到不同采购负责人）。
- **预警邮件只能单实例节流**：节流窗口存在进程内（`AtomicReference`），多实例部署时会各自发信；要真限流需换成 Redis 计数。
- **单据无中文/无条码**：PDF 用内置 Helvetica/Courier，只能出英文/德文（中文字形需内嵌 CJK 字体子集，会让镜像变大）；单号目前是等宽大字而不是真条码（需引入 ZXing 生成 Code128）。
- **MinIO 为单节点演示形态**：无擦除码/多节点、无生命周期与保留策略、无 TLS，演示凭据写在 compose 里（生产须外置密钥 + 开启服务端加密）。
- **单据不会自动清理**：每次重打都新增一个版本（为了留痕），当前没有保留期/归档策略，长期运行需要配对象生命周期或定期扫导。
- **面单缺收货地址**：平台订单的收货地址未入库（`orders` 表只存了履约仓），因此面单打印的是「仓内作业联」+ 目的地国家；真实面单需补齐收货地址（或直接对接承运商取面单）。
- ~~DDL 非版本化~~ ✅ **已解决（P0-4）**：数据库结构由 Flyway 版本化管理（`db/migration/{order,inventory}`），`ddl-auto=validate`。

### 后续路线（详见 `todo.md`）
1. ~~P0-1 `order-service` 持久化~~ ✅ **本轮完成**
2. ~~P0-2 数据库初始化脚本~~ ✅ **本轮完成**
3. ~~P0-3 全量容器验证~~ ✅ **本轮完成**（`container-verify.sh` EXIT=0，10/10 容器 healthy；断言数随后续轮次递增，当前 **96 项 / 18 容器**）
4. ~~P0-4 Flyway 版本化迁移~~ ✅ **本轮完成**（`db/migration/{order,inventory}` + `ddl-auto=validate`，替换 `ddl-auto: update`）
5. ~~P0-5 `inventory-service` 库存 CRUD~~ ✅ **本轮完成**（`GET /api/inventory/{sku}` + `POST /api/inventory/release`；扣减→查询→释放 闭环单测 12 个全绿）
6. ~~**P1 M5 可观测性**：Micrometer + Prometheus + Grafana + Loki。~~ ✅ **已完成（P1-1，2026-09-17）**：Micrometer + Prometheus + Grafana（含业务指标，当时 9 面板；P1-1b 又加 2 个日志面板 → 共 11）；Loki 日志聚合归入 P1-1b
7. ~~**P1 M5 限流熔断**：Resilience4j + Redis 令牌桶。~~ ✅ **已完成（P1-2，2026-09-16）**
8. ~~**P1-1b 日志聚合**：Loki + Promtail（需拉取镜像），接入各服务 stdout 并关联 traceId~~ ✅ **已完成（2026-09-17）**：Promtail 经 Docker API 采集 → Loki 存储 → Grafana 日志面板；日志里的 traceId 可一键跳 Zipkin
9. ~~**P1-3 追踪链路**：Spring Cloud Sleuth/Micrometer Tracing + Zipkin。~~ ✅ **已完成（2026-09-17）**：Micrometer Tracing + Brave + Zipkin，跨服务同一 traceId 实测通过（含 WebFlux 网关→Servlet 下游）
10. ~~**P1-4 平台契约测试**：WireMock 模拟限流/超时/分页。~~ ✅ **已完成（2026-09-17）**：`AmazonSpApiStrategy` 升级为真实 HTTP 实现（LWA + 订单列表分页 + 商品明细），26 个 WireMock 契约/边界测试 + 诊断探针 + `aslp_wiremock` 容器桩（端到端 18 项断言）
11. ~~**P1-5 邮件真实化**：MailHog + 补货邮件模板化。~~ ✅ **已完成（2026-09-17）**：MailHog（真实 SMTP + Web 收件箱）+ Thymeleaf HTML 模板 + multipart/alternative；恢复 `management.health.mail`；新增**邮件节流**与手动触发端点（巡检 60s、发信 30m）
12. ~~**P1-6 对象存储**：MinIO 启用 + 面单/报关单 PDF 存储。~~ ✅ **已完成（2026-09-17）**：镜像改 quay.io（Docker Hub 的 `minio/minio` 已下线）；OpenPDF 生成两类单据，对象键按订单分组 + 版本留痕 + sha256 元数据；预签名 URL 可用；`minio` 健康组件
13. **P2 M6 服务治理**：Eureka/Nacos + Spring Cloud Config，网关恢复 `lb://`。
14. **P2 M6 异步解耦**：Kafka 订单异步流水线 + 死信队列。
15. **P3 M7 生产化**：CI/CD、K8s 清单、SonarQube、Testcontainers 集成测试、安全基线。

---

## 11. 故障速查

| 现象 | 排查方向 |
|---|---|
| `NoClassDefFoundError` / 主类找不到 | 确认 VS Code Java 自动构建已关闭（`.vscode/settings.json`）；`mvn clean package -T 1C` 重试 |
| 新接口返回 404 / 服务启动报 `Failed to start bean 'webServerStartStop'` | **端口被上一轮遗留的旧进程占用**。执行 `pkill -9 -f smart-logistics-platform` 后重启 |
| 网关返回 401 | 本地默认 profile 应放行；若设了 `SPRING_PROFILES_ACTIVE=docker` 需带 `Authorization: Bearer <token>` |
| 网关返回 503 | 下游服务未启动或端口不符，查 `gateway/src/main/resources/application.yml` 路由表 |
| 服务启动报 DataSource 错误 | 先 `docker compose up -d aslp_postgres aslp_redis`，并核对 §7 配置表 |
| 库存扣减报 `StaleStateException` / 0 rows updated | 检查 `inventory.version` 是否被写成 NULL（种子数据必须为 `0`） |
| 邮件相关告警刷屏 | 已关闭 mail 健康探测；如需真实邮件请部署 SMTP 并改 `spring.mail.host` |
| Docker 构建缓慢 | `docker compose build` 会命中 POM 预下载层；`target/` 已在 `.dockerignore` 中排除 |
| 网关启动报 `URISyntaxException: Expected scheme-specific part at index 5: http:` | 路由 URI 的主机名含下划线（如 `aslp_order_service`）—— Java 的 `java.net.URI` 无法表示。改用 compose 中的连字符别名（`aslp-order-service`），见 §7 |
| 容器内服务报 Redis `Connection refused: localhost:6379` | 确认写的是 `spring.data.redis.*`（starter 不识别 `redisson.singleServerConfig.*`），见 §7 / §9 #22 |
| `.DS_Store` 出现在 `git status` | macOS Finder 生成。已在**仓库根** `.gitignore` 忽略，并一次性执行 `git rm --cached .DS_Store` 取消跟踪 |
| 接口突然成片返回 429 | 网关限流生效。确认是否命中受限路由（`POST /api/orders/pull` 1 次/秒、`/api/auth/**` 10 次/秒）；响应头 `X-RateLimit-*` 给出桶容量与剩余。验证限流请用**并发突发**而不是串行紧接两次（见 §9 #28） |
| `POST /api/routes/optimize` 报 `no search-strategy found` | **已修复（P1-2b）**：原 `VrpRouteService` 用了空的 `SearchStrategyManager`，见 §9 #27。若重现，说明有人改回了裸构造写法——`VrpRouteServiceTest.bareSearchStrategyManagerStillFails` 是回归证据 |
| `POST /api/routes/optimize` 返回 `feasible:false` / `无可行路线` | 运力不足：核对车辆 `capacity` 与作业 `demand`（jsprit 的容量维度是 `int`，小数会被拒绝）；回执的 `unassignedJobIds` 会列出未被指派的作业 |
| `POST /api/routes/optimize` 返回 400 而不是解 | 入参校验未过（坐标必须成对且在范围内、容量/需求必须为正整数）。**不要用 0 代替“没传”**：0 是合法坐标也合法数值，会被当真 |
| Prometheus 里某个服务 `health=down` 且报 **400** | Host 头含下划线（抓取目标写了容器名）；Tomcat 在进应用前就回 400，应用日志无记录。改用连字符别名，见 §9 #30 与 monitoring/prometheus/prometheus.yml |
| Prometheus 里网关报 **401**、auth-service 报 **403** | 观测端点未进安全链白名单（403 而非 401 是因为该服务关了 httpBasic），见 §9 #31 |
| 断言“明明有却报没有”（大响应体） | `set -o pipefail` 下 `echo "$body" \| grep -q` 会因 grep 提前退出而 SIGPIPE（退出码 141）→ 假失败。改用 here-string：`grep -qF -- "$needle" <<< "$body"`，见 §9 #32 |
| 服务启动报 `/tmp/tomcat.xxxx: No space left on device` | Docker 虚拟机磁盘满（与业务无关）。`docker image prune -f` + `docker builder prune -f` 清理（**别加 `--volumes`**，本机还跑着其他项目的 13GB 数据卷），见 §9 #34 |
| Grafana 看板面板全是 **No data** | 确认数据源 uid 仍为 `aslp-prometheus` / `aslp-loki`（看板 JSON 按 uid 引用）；再确认该指标/日志名真实存在：`curl localhost:9090/api/v1/label/__name__/values`、`curl localhost:3100/loki/api/v1/labels` |
| Grafana 里看不到日志 | 先看 Promtail 采集指标：`curl localhost:9080/metrics | grep promtail_docker_target_entries_total`。为 0 说明 docker_sd 没发现容器（检查 `aslp_` 前缀过滤与 socket 挂载）；有值说明是 Loki 侧或查询条件问题（标签是 `service` / `project`，不是容器名） |
| 日志里没有「在 Zipkin 中查看这条链路」按钮 | 该按钮来自 Loki 数据源的 `derivedFields`；确认 `monitoring/grafana/provisioning/datasources/loki.yml` 已加载（重启 `aslp_grafana`）且日志行确实含 `[32hex-16hex]` |
| 按 `serviceName` 查 Zipkin 查不到业务 span | 监控抓取（`/actuator/health`、`/actuator/prometheus`）也会产生 trace，会把固定 `limit` 的窗口洗掉。改用 `?spanName=aslp.vrp.solve` 精确查，见 §9 #37 |
| Zipkin 里看不到 **gateway**，日志报 `Host is not specified` | 上报地址用了下划线主机名（`aslp_zipkin`）。WebFlux 网关走 WebClient -> `java.net.URI`，解析不出 host。改成连字符别名 `aslp-zipkin`，见 §9 #35 |
| Zipkin 里每个服务都有 span，但**看不到跨服务链路** | 上下文中断：要么某一层没上报（先看 `/api/v2/services` 是否 6 个服务都在），要么传播格式不一致。本项目用 Boot 3 默认的 W3C `traceparent`，各服务不要各自改 `management.tracing.propagation.type` |
| 日志里搜不到 traceId | 确认该服务在 classpath 里有 `micrometer-tracing-bridge-brave`（否则 MDC 不会有 traceId）；再确认日志格式未被自定义 `logging.pattern.*` 覆盖掉 Boot 3 默认的 correlation 段 |
| 同一条链路的耗时指标数值偏大 / 计数翻倍 | 同时用了 Observation 与同名手写 `Timer` → 一份埋点记两次。业务 span 应只用 Observation，指标由 `MeterObservationHandler` 产出 |
| 契约探针返回 **401 / 403** | 探针走网关，docker profile 强制 JWT：先 `POST /api/auth/login?username=admin` 取 token（见 §4）；403 则是角色不足 |
| 探针报 `"errorType":"CredentialsMissing"` | 没配 SP-API 凭据（`aslp.order.amazon.client-id/-secret/-refresh-token`）。**本地默认就是这个状态**（不发任何请求）；想验证契约请走容器链路（桩容器）或设 `aslp.order.mock-enabled=true` 走 Mock |
| 探针报 `SpApiClientException`（`retryable:false`） | 平台返回 400/403/404：**参数或授权有问题，重试无意义**（不该进重试/熔断）。看 `errorMsg` 里的状态码定位是哪个参数或哪项授权 |
| 探针报 `RateLimitedException` | 429 限流，`retryAfterSeconds` 就是平台要求的退避时间。容器里这是 `marketplaceId=AMZN-RATE-LIMITED` 的场景桩；真实环境应降低拉取频率或申请配额 |
| 容器里契约类断言失败（桩未生效） | 先 `docker inspect -f '{{.State.Health.Status}}' aslp_wiremock`；再 `curl -s localhost:8099/__admin/mappings \| grep -c urlPath`（应为 11）。桩是**只读挂载**的 `./wiremock`，改完需 `docker compose restart aslp_wiremock` |
| 断言「明明命中却报不匹配」（期望值里含空格） | 检查是否对**探针响应体**做了 `tr -d ' '`：它是 Jackson 紧凑 JSON，**字符串值里的空格是有意义的**（如商品名 `AeroSleep 婴儿床 6 件套`）。只对 WireMock 管理端点的带空格 JSON 去空白，见 §9 #39 |
| `docker compose build` 报 `Could not transfer artifact ... Remote host terminated the handshake` | Maven Central 网络抖动（构建容器内下载依赖时握手被打断），**与代码无关**：重跑 `docker compose build` 即过（实测第二次全绿）。不要先去改依赖版本 |
| 冒烟脚本报 `syntax error near unexpected token` | 改完脚本先跑 `bash -n scripts/smoke-test.sh` 做静态解析（毫秒级），再执行，见 §9 #40 |
| 服务启动报 `required a bean of type 'XxxProperties'` | 新建的 `@ConfigurationProperties` 类没写进 `@EnableConfigurationProperties` —— **编译和单测都不会报错**，只有启动上下文会炸，见 §9 #43 |
| 服务启动报 `Invalid fixedDelayString value "60s"` | `@Scheduled` 的 String 形式只认毫秒数或 ISO-8601（`PT60S`），不认 Boot 的 `60s` 简写，见 §9 #44 |
| 补货邮件没收到 | 先看 `GET /api/inventory/warnings/status`（未命中阈值？还在节流窗口内？），再看 `/actuator/health` 的 `mail` 组件与 `docker logs aslp_inventory_service \| grep 补货邮件`（SMTP 不通会记 WARN），最后开 http://localhost:8025 看收件箱 |
| 补货邮件“收件箱里有，但断言查不到” | 邮件主题/正文是 **quoted-printable** 编码、且 MailHog（Go）会把 `<` `>` `&` 转义成 `\u003c` —— **直接 grep 原文一定假失败**。用 `email` 库解码 `Raw.Data` 后再断言，见 §9 #47 |
| MinIO 报 `InvalidAccessKeyId` | 先确认服务端凭据（`docker exec aslp_minio mc alias set probe http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"` 后 `mc ls probe`），再查客户端：**Spring 占位符是 `${VAR:default}`，写成 shell 风格 `${VAR:-default}` 会让默认值带上前导减号**，见 §9 #45 |
| 预签名 URL 报 `Failed to connect to localhost:9000` / 上传成功但接口 503 | 预签名客户端用对外端点（容器内不可达），而 SDK 签名前会去查桶区域。**显式配 `aslp.minio.region`** 即可跳过查询，见 §9 #46 |
| 浏览器打开预签名 URL 报签名不匹配 | 不要手工改 URL 的主机名：**签名覆盖 Host 头**，改了就不匹配。应配 `aslp.minio.public-endpoint` 并让它签（应用已用双客户端处理） |
| 断言 400/404 类故障时“拿不到响应体” | `curl -f` 会把 4xx/5xx 当成失败并丢掉响应体；预期是业务拒绝时用 `curl -s` + 自行断言状态码/报文体，见本轮冒烟脚本写法 |

---

## 12. 命名与脱敏约定（信息安全）

本项目为**技术演示与架构验证**用途，所有对外可见内容均已做脱敏处理：

| 项 | 约定 |
|---|---|
| Java 包根 | 统一 `com.aslp.*`（`aslp` = Smart Logistics Platform 项目缩写，不含任何主体标识） |
| Maven groupId | `com.aslp` |
| 容器 / 网络 / 数据卷 | `aslp_*` 前缀（`aslp_postgres`、`aslp_net`、`aslp_pg_data` 等） |
| 供 URI 寻址的主机名 | 下划线不是合法 `hostname` 字符（RFC 2396）→ 被网关路由寻址的服务额外声明**连字符网络别名**（`aslp-order-service` 等）供 URI 使用；`container_name` 仍为 `aslp_*` |
| 邮件地址 | `warehouse-manager@aslp.internal`（保留域名 `.internal`，不可投递） |
| 密钥 | 仅以占位符 `aslp-dev-secret-key-change-me-in-production-*` 出现；生产必须由 `ASLP_JWT_SECRET` 注入 |
| 仓库 / 组织名 | 文本中的克隆地址为占位示例 |

> 约定：**不得**在代码、配置、文档、测试数据中写入真实企业名称、门店地址、联系人、订单号或密钥。
> 第三方平台名（Amazon / eBay / DHL / DPD / OpenStreetMap 等）仅作为**对外集成对象**出现，属业务必需。

---

## 13. 新人阅读指引（代码地图）

> 目标：让第一次接触本项目的人（或三个月后的自己）在半小时内建立「文件 -> 职责 -> 先看哪个」的地图。

### 13.1 先确认你在哪一层（多项目工作区）

本仓库是一个 git 仓库装了多个独立项目，本项目只是其中之一：

```text
CJJ_JAVA_WORKSPACE/            <- git 仓库根（.git 在这里）
├── .gitignore                 # 仓库根忽略规则（macOS 元数据，含 .DS_Store）
├── smart-logistics-platform/  # 本项目（项目内另有 .gitignore，管 target/ IDE 日志等）
└── cloud-native-api-gateway/  # 另有 6 个兄弟项目并列，各自独立构建
```

> 因此 `git status` 看到的路径都带 `smart-logistics-platform/` 前缀，提交发生在仓库根。

### 13.2 顶层文件

| 文件 | 作用 |
|---|---|
| `pom.xml` | 聚合 POM：parent=`spring-boot-starter-parent:3.3.0`、`<java.version>21</java.version>`、`spring-cloud.version=2023.0.3`；声明 6 个 module；`pluginManagement` 统一 compiler/surefire/boot 插件（含关闭增量编译的 APFS 修复） |
| `Dockerfile` | 多阶段：`maven:3.9-eclipse-temurin-21-alpine` 构建 → `eclipse-temurin:21-jre-alpine` 运行；`ARG MODULE` 选择模块；非 root 运行 + 健康检查 |
| `docker-compose.yml` | PG16 / Redis7 / ZooKeeper+Kafka7.6 / 6 个 Java 服务；`x-java-service` 锚点复用；`healthcheck` + `depends_on: service_healthy`；URI 安全连字符别名 |
| `.dockerignore` | 排除 `target/`、`.git/`、`.gradle/`，让 POM 预下载层可被 6 个镜像复用 |
| `.gitignore` | 项目级忽略：`target/`、IDE、`.DS_Store`、日志；**特别注明不能写 `*.sql`**（会吞掉 Flyway 迁移脚本） |
| `.vscode/settings.json` | `java.autobuild.enabled=false` —— 避免 JDT 语言服务器与 Maven 抢占 `target/classes` |
| `scripts/container-verify.sh` | P0-3 容器全量验证：构建镜像 → 启动 → 等 healthy → 跑断言 |
| `scripts/smoke-test.sh` | 端到端冒烟（96 项断言）；`--external` 可直接打已运行的容器 |
| `wiremock/mappings/*.json` | P1-4 平台契约桩（11 个）：LWA 换令牌 / 订单列表两页 / 商品明细（含一个 500）/ 限流 / 5xx / 超时 / 4xx / 空结果。靠 `MarketplaceIds` 查询参数分流 |
| `services/inventory-service/src/main/resources/templates/email/replenishment.html` | P1-5 补货邮件模板（Thymeleaf）：行内样式 + 表格布局 |
| `services/order-service/src/main/java/com/aslp/order/document/` | P1-6 单据层：`DocumentType`（白名单枚举）/ `PdfDocumentWriter`（OpenPDF 生成）/ `DocumentNotFoundException`·`DocumentStorageException`（404 vs 503 语义） |
| `monitoring/prometheus/prometheus.yml` | P1-1 抓取配置；**targets 用连字符别名**（下划线主机名会被 Tomcat 判 400） |
| `monitoring/grafana/provisioning/**` | 数据源（uid 固定 `aslp-prometheus`）与看板加载器；容器启动自动生效 |
| `monitoring/grafana/dashboards/aslp-overview.json` | ASLP 总览看板（11 面板，含日志面板；PromQL 均已对照真实指标名验证） |
| `test-data/mock-test-data.json` | 假数据：Amazon/eBay 订单、库存预警、VRP、DHL/DPD 轨迹 |
| `readme.md` / `todo.md` | 架构与接口文档 / 按轮次增量记录的进度与缺陷台账 |

### 13.3 六个模块逐文件职责

**`gateway/`（Spring Cloud Gateway + BFF，WebFlux，:8080）**

| 文件 | 职责 |
|---|---|
| `GatewayApplication.java` | 启动类 |
| `bff/BffOrderController.java` | BFF 聚合端点 `GET /bff/orders/search`（多条件分页，筛选项按需回显） |
| `bff/OrderQueryRequest.java` | 查询参数对象（Spring Validation 校验） |
| `security/VpnSecurityConfig.java` | WebFlux 安全链：dev 全放行；docker 强制 JWT + `roles` -> `ROLE_*` 映射 + HS384 解码器 |

**`services/auth-service/`（M1 认证，:8084）**：`config/SecurityConfig`（放行 `/api/auth/**`）、`controller/AuthController`（`/login` 签发、`/health`）、`service/JwtTokenService`（JJWT 0.12.5，HS384，claims 含 roles/scope）

**`services/order-service/`（M1/M4，:8081）**

| 文件 | 职责 |
|---|---|
| `entity/OrderRecord.java` | `orders` 表映射：`orderId` 唯一键、`@Version` 乐观锁、`errorTag` 异常标签 |
| `repository/OrderRepository.java` | 派生查询：单号 / 多条件分页 / 状态与异常统计 |
| `service/OrderPullService.java` | **主流程**：策略拉取 → DTO 标准化 → 幂等落库（重复只更新）→ 异常打标，整体 `@Transactional` |
| `controller/OrderController.java` | 查询、多条件分页、统计、`/correct` 客服修正 |
| `controller/OrderPullController.java` | `POST /pull` 触发拉取 |
| `controller/OrderStateController.java` | 状态机触发与查询 |
| `statemachine/` | `OrderStates` / `OrderEvents` / `SimpleOrderStateMachine`（纯 Java）/ `OrderStateMachineService`（按 `orderId` 隔离实例） |
| `strategy/` | `OrderPullStrategy` 接口 + `MockAmazonStrategy`（故障演练注入点）/ `AmazonSpApiStrategy`（P1-4 起为真实 HTTP 实现）+ `OrderDto` / `OrderPullResult` / `PlatformUnavailableException` / `PlatformFailureSwitch` |
| `spapi/` | P1-4 协议层（防腐层）：`SpApiProperties`（配置）/ `LwaTokenClient`（换令牌 + 缓存）/ `SpApiOrderClient`（分页 + 异常分类）/ `SpApiOrderMapper`（→ M1 统一 DTO：城市按配置映射到履约仓，地址缺失打 `ADDRESS_INVALID`）/ `SpApiOrdersPage`・`SpApiOrderItemsPage`（报文）/ `SpApiHttp`（统一超时）/ `RateLimitedException`・`SpApiClientException`（可重试 vs 不可重试） |
| `config/SpApiConfig.java` | 装配 SP-API 客户端（注入 Boot 的 `RestClient.Builder`，保留观测/链路透传）；bean 与 `mock-enabled` **无关** —— 探针无论走不走真实策略都能体检对外契约 |
| `document/` | P1-6 单据层：`DocumentType`（白名单枚举，路径参数只能取这两个）/ `PdfDocumentWriter`（OpenPDF 生成面单与报关单，压缩级别 0 便于搜单号）/ `DocumentNotFoundException`·`DocumentStorageException`（分别对应 404 与 503 语义） |
| `config/MinioConfig.java` + `MinioProperties` + `DocumentProperties` | 两个 MinioClient（内网上传下载 / 对外预签名）+ 启动建桶（失败只告警，不阻塞启动）；三个 `@ConfigurationProperties` 都必须在 `@EnableConfigurationProperties` 里注册，否则启动失败（§9 #43） |
| `service/DocumentStorageService.java` | 对象键命名、上传、下载最新一版、列举历史版本、预签名 URL、桶可达性探测（返回错误原因而不是只有布尔） |
| `health/MinioHealthIndicator.java` | `/actuator/health` 的 `minio` 组件（带桶名/端点/失败原因）—— 单据是对外凭证，存储挂了必须能被看见 |
| `controller/DocumentController.java` | `POST/GET /api/orders/{orderId}/documents/{type}` 与列表；把 400/404/503 语义映射到 HTTP 状态码 |
| `controller/SpApiProbeController.java` | `POST /api/orders/spapi/probe` 诊断探针：固定形状诊断报文（`ok/errorType/retryable/pages/count`），**无副作用**（不落库、不经熔断器） |
| `task/OrderSyncTask.java` | 定时流水线式导入（`aslp.order.sync.enabled`） |
| `task/DatabaseBackupTask.java` | `pg_dump` 定时备份，参数化可开关 |

**`services/inventory-service/`（M2，:8082）**

| 文件 | 职责 |
|---|---|
| `entity/InventoryItem.java` | `inventory` 表映射：`availableQty` / `lockedQty` 双状态 + `@Version` |
| `repository/InventoryRepository.java` | `findBySkuAndWarehouseCode`、`findBySku`、低库存查询 `findByAvailableQtyLessThan` |
| `service/InventoryLockService.java` | **并发核心**：Redisson `RLock`（键 `inventory:lock:{sku}:{warehouse}`）+ 乐观锁；扣减（预占）与释放（回退） |
| `service/ReplenishmentMailService.java` | P1-5：Thymeleaf 渲染 HTML + multipart/alternative 双正文发送；渲染在 try 外（模板错不该被当成 SMTP 故障），发送异常一律吞掉返回 false |
| `resources/templates/email/replenishment.html` | P1-5 邮件模板（行内样式 + 表格布局，含明细表/告急标记/建议动作） |
| `config/MailTemplateConfig.java` + `WarningProperties` | 邮件专用 Thymeleaf 引擎（不引 web starter，不给 Web 层注册视图解析器）；阈值/收件人/节流/巡检周期配置 |
| `task/InventoryWarningTask.java` | 定时巡检 + **邮件节流**（扫描 60s、发信 30m）；返回 `WarningScanResult` 供运维端点回显 |
| `dto/InventoryView.java` | 查询响应：SKU 汇总 + 各仓明细（record，天然规避 `Map.of` 的 null 限制） |
| `controller/InventoryController.java` | `/health`、`/deduct`、`GET /{sku}` 查询、`POST /release` 释放、`GET /warnings/status`、`POST /warnings/trigger` |

**`services/route-service/`（M3，:8083）**

| 文件 | 职责 |
|---|---|
| `engine/FreightRule.java` → `engine/EuropeDhlRule.java` | 运费规则接口与欧洲 DHL 实现（基础费 + 距离×0.12 + 重量×0.35） |
| `engine/FreightEngine.java` | 按承运商选规则（DHL / DPD） |
| `engine/GeoDistance.java` | Haversine 大圆距离（km）。**约定：第一个参数纬度、第二个经度**；与 jsprit 内置 `GreatCircleCosts` 相反，类注释里写明了原因 |
| `engine/HaversineCostModel.java` | jsprit 成本模型：用真实 km 替代默认「坐标单位欧氏距离」，并把时间折算成小时 |
| `engine/VrpProblemFactory.java` | **入参门面**：JSON → jsprit 问题 + 集中校验（坐标成对、容量/需求正整数、id 唯一、求解参数区间）；内置演示问题 |
| `dto/OptimizeRequest.java` | 求解入参（record，字段用包装类型以区分“没传”与“传 0”） |
| `dto/VrpPlan.java` | 求解出参：逐车路线、停靠顺序、里程(km)、载重、未指派作业（字段与 `mock-test-data.json` 的 `routeResults` 对齐） |
| `service/VrpRouteService.java` | jsprit 求解与结果翻译（固定随机种子保证可复现） |
| `service/TrackingService.java` | DHL/DPD 轨迹拉取（硬编码 `RestTemplate` + 真实端点，暂未单测） |
| `controller/RouteController.java` | `/health`、`POST /optimize`（接入真实引擎；入参错误 400） |

**`services/report-service/`（M1 报表，:8085）**：`controller/ReportController` —— ECharts 看板数据源

### 13.4 配置怎么读（最容易踩坑的地方）

| 路径 | 含义 |
|---|---|
| `src/main/resources/application.yml` | 默认 profile：面向本机（`localhost` 数据库 / Redis） |
| `src/main/resources/application-docker.yml` | `docker` profile：面向容器（服务名寻址、定时任务与备份开启） |
| `src/main/resources/db/migration/<svc>/V<n>__<desc>.sql` | Flyway 迁移；order 读 `db/migration/order`，inventory 读 `db/migration/inventory`，两服务共用同一物理库但历史表独立 |

三条血泪教训（均已登记到 §9）：只有 `SPRING_PROFILES_ACTIVE=docker` 才会加载 docker 覆盖；`redisson.singleServerConfig.*` 不会被 starter 绑定（要写 `spring.data.redis.*`）；容器主机名的下划线对 `java.net.URI` 非法（网关路由要用连字符别名）。

### 13.5 测试怎么读

| 类型 | 位置 | 特点 |
|---|---|---|
| 切片测试 `@WebMvcTest` | `*/controller/*Test.java` | 只加载 Web 层，`@MockBean` 掉服务与仓库，断言 HTTP 状态与 JSON |
| 纯单元测试 | `*/service/*Test.java`、`*/engine/*Test.java`、`*/statemachine/*Test.java` | Mockito 驱动，不启动 Spring；库存闭环测试用 Mock 的 `RLock` + 真实业务逻辑；`GeoDistanceTest` / `HaversineCostModelTest` 用真实城市坐标把「纬度在前」的约定钉死 |
| 端到端断言 | `scripts/smoke-test.sh` | 经网关 8080 打真实服务，覆盖路由、鉴权与业务链路；限流用并发突发断言（见 §9 #28），带 JSON body 的断言用 `grep -F`（见 §9 #29）；涉及监控的两项做**有界轮询**（见 §9 #33）；断言一律用 here-string 而非管道（见 §9 #32）；含空格的期望值不要对响应体去空白（见 §9 #39） |
| **契约测试** | `order-service/src/test/java/com/aslp/order/spapi/*ContractTest.java` | 用 WireMock 起在**随机端口**上做真实 HTTP（平台是假的、客户端是真的）：`SpApiOrderClientContractTest` 覆盖 429/超时/5xx/4xx/401 刷新/令牌缓存/分页/空结果/明细失败；`SpApiRetryClassificationTest` 用真实 `RetryConfig` 锁死「异常分类 ↔ 重试配置」一致；`SpApiTestFixture` 是共享夹具（避免各测试各写一套装配） |
| **邮件/P D F 测试** | `ReplenishmentMailServiceTest` / `PdfDocumentWriterTest` / `DocumentStorageServiceTest` | 邮件用**真实 Thymeleaf 引擎**渲染模板（拼错变量名就报错），并用 `writeTo()` 往返序列化后再断言 MIME（见 §9 #41）；PDF 断言文件头/尾 + 明文内容（压缩级别 0）；存储层 Mock MinioClient，只锁对象键/元数据/异常分类 |

### 13.6 可观测性怎么读（P1-1 / P1-3）

**指标链路**（四个层次，从上往下看）：

| 层次 | 看哪里 | 说明 |
|---|---|---|
| ① 应用埋点 | 各服务 `application.yml` 的 `management.*` + 代码里的 `MeterRegistry` / `Observation` | 配置只决定「暴露什么」；业务指标/span 在 `OrderPullService` / `VrpRouteService` / `InventoryLockService` 中直接写入 |
| ② 抓取 | `monitoring/prometheus/prometheus.yml` | 一个 job（`aslp-services`）扫 6 个服务；targets 必须是连字符别名（§9 #30） |
| ③ 存储与查询 | `http://localhost:9090/targets`、`/graph` | 排查先看 targets 的 `health` 与 `lastError`，再看 PromQL |
| ④ 展示 | `monitoring/grafana/**` → `http://localhost:3000/d/aslp-overview` | 数据源与看板都是文件版本化，改完 JSON 重启 `aslp_grafana` 生效 |

**链路追踪**（P1-3）：冒烟脚本会断言「一条 trace 里同时含 gateway 与 order-service」，所以追溯一条请求的读法是：

```bash
# 1) 看哪些服务在上报（少一个就说明那个服务的上报链断了）
curl -s http://localhost:9411/api/v2/services
# 2) 按服务拉 trace，挑一条跨服务的看 span 树（或直接开 http://localhost:9411/zipkin/）
curl -s "http://localhost:9411/api/v2/traces?serviceName=order-service&limit=20"
# 3) 从日志里的 [traceId-spanId] 直接反查：把 traceId 拼进 Zipkin 地址
#    http://localhost:9411/zipkin/traces/<traceId>
```

**指标命名约定**：Micrometer 名 `aslp.order.pull.requests` → Prometheus 名 `aslp_order_pull_requests_total`（点转下划线、Counter 加 `_total`、Timer 加 `_seconds`）。在应用里断言用前者，在看板里用后者。

**低基数 vs 高基数**：会出现在指标标签上的只能是低基数（平台/结局/有无解这种），否则时间序列会爆炸；高基数（订单数、里程、traceId）只进 span。代码里用 `lowCardinalityKeyValue` / `highCardinalityKeyValue` 显式区分。

**日志**（P1-1b）：

| 层次 | 看哪里 | 说明 |
|---|---|---|
| ① 产出 | 各服务 stdout（无需改代码） | 日志由 Spring Boot 默认 pattern 输出，P1-3 起行内自带 `[traceId-spanId]` |
| ② 采集 | `monitoring/promtail/promtail.yml` + `:9080/targets` | 走 Docker API（不是 tail 文件）；按容器名前缀 `aslp_` 过滤；`level` 标签由 pipeline stage 从行首抽取 |
| ③ 存储与查询 | Loki `:3100/loki/api/v1/query_range` | 标签只保留低基数：`service` / `project` / `container` / `level` |
| ④ 展示 | Grafana 看板底部「服务日志」面板 | 数据源 `aslp-loki`；行内 traceId 可点开跳 Zipkin |

**三个栈如何串起来**（这是本项目可观测性的重点）：

```text
一次请求 → traceId（P1-3）
   ├─ 指标：按 service/outcome 聚合，看趋势（P1-1）
   ├─ 链路：Zipkin 看 span 树与各段耗时（P1-3）
   └─ 日志：Loki 里搜到同一行，点 traceId 回到链路（P1-1b）
```

### 13.7 外部平台契约怎么读（P1-4）

平台接入是本项目**唯一一处代码之外的契约**（亚马逊说了算），所以它的读法要分三层看：

| 层次 | 看哪里 | 能回答什么问题 |
|---|---|---|
| ① 协议层 | `order-service/spapi/SpApiOrderClient` + `LwaTokenClient` | 调用哪些路径、带什么头、怎么分页、**每个状态码映射成哪个异常** |
| ② 契约夹具 | `src/test/resources` 之外的 `order-service/src/test/java/.../SpApiTestFixture` + WireMock 桩 | 「我们理解的平台」长什么样；改一行字段名会有哪些用例变红 |
| ③ 运行期 | `wiremock/mappings/*.json` + `aslp_wiremock` 容器 + `POST /api/orders/spapi/probe` | 打包进镜像、跨容器网络之后，对外契约是否还通；异常分类在真实 HTTP 上是否成立 |

```bash
# 1) 一眼看出「通不通 + 哪一类失败」（探针报文固定形状，适合写断言与看板）
curl -s -X POST http://localhost:8080/api/orders/spapi/probe -H "Authorization: Bearer $TOKEN"
#    → {"ok":true,"pages":2,"count":3,...} 或
#      {"ok":false,"errorType":"RateLimitedException","retryable":true,"retryAfterSeconds":7}

# 2) 平台到底被调了几次（分页是否真的翻页，只有计数能证明）
curl -s -X POST http://localhost:8099/__admin/requests/count \
  -H 'Content-Type: application/json' -d '{"method":"GET","urlPath":"/orders/v0/orders"}'
#    → {"count":2}

# 3) 桩场景怎么切换：靠 MarketplaceIds 查询参数分流（不用重启容器）
#    A1PA6795UKMFR9=正常两步分页 | AMZN-RATE-LIMITED=429 | AMZN-TIMEOUT=延迟3s
#    AMZN-SERVER-ERROR=503 | AMZN-BAD-REQUEST=400 | AMZN-EMPTY=空结果
```

**三条设计约束**（改这一块前先读，否则很容易“修好一个、弄坏一个”）：
1. **重试语义写在异常继承线上**：429（`RateLimitedException` 继承 `PlatformUnavailableException`）/5xx/超时 → 命中 `resilience4j.retry.*.retry-exceptions`；其他 4xx（`SpApiClientException`）**不在**该继承体系内，因此不会被重试。新增异常时先想清楚它落在哪一边。
2. **可重试的必须往上报，不可重试的必须就地转成 `success=false`**：吞掉前者 → 重试/熔断永不触发（平台故障静默变成“拉取 0 单”）；抛出后者 → 白白重试 3 次并计入熔断失败率。
3. **任何“部分失败”都要显式表达**：商品明细失败 → 商品名回退占位符（订单照常入库）；分页被上限截断 → `truncated=true` + 日志告警。**不要用“看起来正常”掩盖数据不完整**。

### 13.8 邮件与单据怎么读（P1-5 / P1-6）

这两块都属于「本系统向外的交付物」（发给人的邮件、随货走的单据），读法都是 **三层：配置 → 代码 → 收件人/存储侧看到的实物**。

**邮件（P1-5）**

| 层次 | 看哪里 | 能回答什么问题 |
|---|---|---|
| ① 配置 | `aslp.inventory.warning.*`（阈值/收件人/节流/巡检周期） | 为什么没发？阈值多少、多久发一次、发给谁 |
| ② 代码 | `InventoryWarningTask.scan()` + `ReplenishmentMailService` | 节流是否生效、附件/双正文怎么拼、失败怎么处理 |
| ③ 实物 | http://localhost:8025（MailHog 收件箱）或 `/api/v2/messages` | 收件人到底看到什么（HTML 渲染效果、主题、明细表） |

```bash
# 运维三连：为什么没收到邮件？
curl -s http://localhost:8080/api/inventory/warnings/status -H "Authorization: Bearer $TOKEN"   # 阈值/低库存/还要等多久
curl -s http://localhost:8082/actuator/health | python3 -m json.tool | grep -A3 '"mail"'      # SMTP 通不通
curl -s http://localhost:8025/api/v2/messages | head -c 200                                   # 收件箱里有什么
```

> 断言邮件内容时**必须解码**：主题/正文是 quoted-printable，MailHog（Go）又把 `<` `>` `&` 转义成 `\u003c`（见 §9 #47）。

**单据（P1-6）**

| 层次 | 看哪里 | 能回答什么问题 |
|---|---|---|
| ① 配置 | `aslp.minio.*`（端点/对外端点/区域/桶）+ `aslp.document.*`（抬头/HS/申报价值） | 存到哪、预签名能不能被浏览器打开、单据上印什么 |
| ② 代码 | `DocumentStorageService`（对象键/版本/sha256）+ `PdfDocumentWriter`（排版/字体） | 重打会不会覆盖、要不要留痕、为什么中文印不出来 |
| ③ 实物 | `POST/GET /api/orders/AMZ-1001/documents/{type}`、MinIO 控制台 :9001 | 单据长什么样（下载 PDF 直接看）、桶里到底有几个版本 |

```bash
# 生成 → 下载 → 看版本（重打会新增版本，不是覆盖）
curl -s -X POST http://localhost:8080/api/orders/AMZ-1001/documents/shipping-label -H "Authorization: Bearer $TOKEN"
curl -s -o /tmp/label.pdf http://localhost:8080/api/orders/AMZ-1001/documents/shipping-label -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/orders/AMZ-1001/documents -H "Authorization: Bearer $TOKEN"   # count = 版本数
```

**三条设计约束**（改这一块前先读）：
1. **通知（邮件）与凭证（单据）不是同一等级**：邮件发不出去只能降级为 WARN（不能让库存巡检挂掉）；而单据写不进去必须暴露为 503 + 健康检查 DOWN（作业员打不了单是硬故障）。这个区分直接体现在两处的异常处理与 `management.health.*` 开关上。
2. **重打必须留痕**：对象键带 UTC 时间戳，「最新一版」= 键最大者。不要去实现「同名覆盖」——物流纠纷里打印次数与内容就是举证材料。
3. **单据正文只用 ASCII**：PDF 内置字体没有中文字形；需要中文单据就要内嵌 CJK 字体子集（会显著增大镜像/内存），目前是按英文/德文面单口径做的（见 §10 限制）。

### 13.9 建议的阅读顺序

1. `docker-compose.yml` —— 先看系统长什么样、谁依赖谁
2. `readme.md` §2 / §4 —— 技术栈与接口速查
3. `gateway/.../application-docker.yml` + `security/VpnSecurityConfig.java` —— 流量入口与鉴权
4. `order-service/service/OrderPullService.java` —— 最能体现工程能力的主流程（策略 + 幂等 + 事务）
5. `order-service/statemachine/SimpleOrderStateMachine.java` —— 纯 Java 状态机与按订单号隔离
6. `inventory-service/service/InventoryLockService.java` —— 分布式锁 + 乐观锁双层防超卖
7. `scripts/smoke-test.sh` —— 反向检验自己对上面各环节的理解

---
