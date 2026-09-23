# Smart Logistics Platform (ASLP) — 海外仓智能物流系统

> 最后更新：2026-09-17 ｜ 版本 `1.0.0-SNAPSHOT`
> 状态：**M1–M4 全部交付 + P0 基线补齐（P0-1～P0-5）+ P1 阶段全部完成（P1-1 指标 / P1-1b 日志 / P1-2 限流容错 / P1-2b VRP 引擎 / P1-3 链路追踪 / P1-4 平台契约测试 / P1-5 邮件真实化 / P1-6 对象存储）；`mvn clean package -T 1C` 全绿（178 个单测全部通过，0 跳过），容器全量验证 18/18 就绪 + 端到端冒烟 96/96 通过**
> 包根：`com.aslp.*`（已脱敏，详见 §12） ｜ 构建：Maven 3.9+ “高铁模式”

---

## 1. 项目定位

> **要现场演示？直接看 [`DEMO.md`](DEMO.md)** —— 15 分钟演示动线、每站的可复制命令与台词、测试数据速查表、被追问时的 10 条对答、现场排障速查。

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
# 一键：构建 6 个服务镜像 → 启动基础设施 + 6 服务 + Prometheus + Grafana + Zipkin + Loki + Promtail + WireMock 契约桩 + MailHog + MinIO → 等就绪 → 跑 124 项断言 → 重启 order/inventory 验证「状态与节流窗口都不在进程内」
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

# ── M4 订单状态机（FBA 退货换标，状态持久化在 DB）─────
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=PAY"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=PICK"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=SHIP"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=FBA_RETURN"
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/trigger?event=RELABEL"
curl      "http://localhost:8080/api/orders/state/DEMO-001"
# 事件轨迹（审计）：含被拒绝的迁移尝试 —— 回答“客户说点过按钮，为什么没生效”
curl      "http://localhost:8080/api/orders/state/DEMO-001/history"
# 重置（夹具复位，同时留一条 RESET 审计）：
curl -X POST "http://localhost:8080/api/orders/state/DEMO-001/reset"
# 无参便捷端点（默认订单 DEMO-001）
curl -X POST "http://localhost:8080/api/orders/state/fba-return"
# 重启后状态仍在（P1-8 持久化硬证据）：
#   docker compose restart aslp_order_service && curl .../api/orders/state/DEMO-001

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

# ── M3 尾程追踪（P1-9：DHL / DPD「一单到底」）────────────
# 单号自动识别承运商（DHL: 10/20 位数字或 JJD/JVGL/GM 前缀；DPD: 14 位数字）
curl "http://localhost:8080/api/routes/tracking/00340434161094000000"
curl "http://localhost:8080/api/routes/tracking/01234567890123"
# 显式指定承运商（识别不了或识别错时的兜底通道）+ 跳过本地缓存
curl "http://localhost:8080/api/routes/tracking/00340434161094000000?carrier=DHL&refresh=true"
# 失败语义：查无此单 404 / 承运商不可用 503（限流带 retryAfterSeconds）/ 上游拒绝 502 / 入参 400
curl -i "http://localhost:8080/api/routes/tracking/00340434161094000002"

# ── M1/M5 报表看板（P1-7：真实聚合，不再是假数据）────────
# 看板数据（订单分布 + 低库存明细）。任一上游不可用 -> 200 + "degraded":true + unavailable 列表
curl http://localhost:8080/api/reports/dashboard
# 单区块查询（前端局部刷新，避免无谓调用另一半）
curl http://localhost:8080/api/reports/orders
curl http://localhost:8080/api/reports/inventory
# 可视化页面（ECharts，15s 自刷新；由 report-service 同源托管，不走网关路由）
open http://localhost:8085/dashboard.html

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
│   ├── smoke-test.sh              # 端到端冒烟测试 124 项断言（支持 --external 打外部服务）
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
    │   │                          #        + statemachine(纯转换规则 + DB 持久化状态与事件轨迹) + strategy(策略模式)
    │   │                          #        + spapi(SP-API 客户端/令牌/映射) + document(单据 PDF) + config + task
    │   └── src/main/resources/db/migration/order/              # P0-4：V1 建表
    ├── report-service/            # M1/M5 报表：client(下游只读客户端) + service(真实聚合 + 降级) + dto(快照/看板)
    │   └── src/main/resources/static/dashboard.html             # P1-7：ECharts 看板页（同源托管，15s 自刷新）
    └── route-service/             # M3 路由：engine(运费规则 + VRP 成本模型/问题装配) + service(VRP 求解/追踪) + dto + tracking(DHL/DPD 客户端 + 状态归一化 + 契约桩)
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
| **状态机持久化（P1-8）** | `OrderStateRecord` / `OrderStateEventRecord` + `db/migration/order/V2` | **DB 是唯一真相源**：每个请求「读库 → 纯逻辑判定 → 事务写回 + 追加事件」，进程内不保存任何状态（因此**重启后状态仍在**，多实例也不会各自为政）。纯转换规则仍在 `SimpleOrderStateMachine` 里（可用初始状态构造 → 从持久化状态继续推进）。**状态与事件同事务**：否则会留下「状态变了但轨迹没记」的不可解释历史。`@Version` 乐观锁防并发推进互相覆盖。事件表**只追加**，连 `accepted=false` 的非法尝试也落库（客服问题「我点了按钮为什么没生效」靠它回答）；`event` 存字符串而非枚举 —— 历史事实不该因枚举重命名而读不出来。读接口**不产生写入**（查询不会把库写胖，也不会让「从未推进」与「重置过」无法区分） |
| **分布式锁** | `inventory-service` `InventoryLockService` | Redisson `RLock` key=`inventory:lock:{sku}:{warehouse}`，等待 2s / 持有 10s 防死锁；配合 `@Version` 乐观锁 |
| **乐观锁** | `InventoryItem.version` | JPA `@Version`，防并发覆盖（种子数据必须写入 `version = 0`） |
| **规则引擎** | `route-service` `FreightRule` → `EuropeDhlRule` | 基础费 + 距离×0.12 + 重量×0.35，DE 区基础费 5.0 / 其他 8.0 |
| **VRP 优化** | `route-service` `VrpRouteService` + `VrpProblemFactory`（jsprit 1.8） | 用官方高层入口 `Jsprit.Builder` 装配算法（**不能裸 `new SearchStrategyManager()`**，那是空策略注册表，见 §9 #27）；问题侧绑定 `HaversineCostModel`（**真实 km**，不用 jsprit 默认的「坐标单位欧氏距离」）；固定随机种子保证结果可复现；出参 `VrpPlan` 给出逐车路线、停靠顺序、里程、载重、未指派作业；无解时返回 200 + `feasible:false` 而非 500 |
| **BFF 聚合** | `BffOrderController` | 多条件分页 + `@NotBlank`/`@Min` 校验参数对象；筛选项按需回显（不传的键不出现） |
| **报表聚合（P1-7）** | `report-service` `ReportAggregationService` + `OrderStatsClient` / `InventoryWarningClient` | 看板数据**真实聚合**：订单分布来自 `GET /api/orders/stats`（数据库侧 `group by`，不把全表捞进内存），低库存来自 `GET /api/inventory/warnings/status`。**不直连别人的数据库、不重算业务口径** —— 阈值只有一处真相源，看板与补货邮件不会打架。上游挂掉时返回 **200 + degraded=true + unavailable 列表**（分块标注 `available:false`），任一依赖抖动不会让整页白屏，也不会把"部分可用"丢掉 |
| **报表图表结构** | `DashboardReport.ChartData` | 服务端把「保序 map」摊平成 `labels[] + values[]`，前端不必再写取 key/value 的胶水代码；顺序由 `LinkedHashMap` 固定，同一份数据每次渲染顺序一致 |
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
| **邮件节流（P1-5 / P1-10）** | `InventoryWarningTask` + `MailThrottle`（`RedisMailThrottle`） | 扫描周期（60s）与发信周期（默认 30m）是两个概念。旧实现「扫一次发 N 封」，在 SMTP 不可用时被「发送失败」掩盖；邮件真通了就会变成**邮件轰炸**。**P1-10 把窗口从进程内 `AtomicReference` 换成 Redis**（`SET NX + TTL`，原子占位 + 自动过期）：多副本共用一个窗口、重启后仍在窗口内。语义细节：**先占位再发信**（并发下只有一个实例真发）、**发信失败归还资格**（否则一次 SMTP 抖动会让告警白停 30 分钟）、`force=true` 绕过并刷新窗口（人工动作立即生效，但不该让自动任务紧接着再发一封）。**Redis 抖动时降级为进程内节流**（fail-open 会轰炸、fail-closed 会失联，降级是唯一兼顾两者的选法）且记 WARN 让降级可见 |
| **单据 PDF 生成（P1-6）** | `order-service` `document/PdfDocumentWriter`（OpenPDF） | 面单（仓内作业联）与报关单（CN22 摘要）两类。选 **OpenPDF（LGPL）而不是 iText 7（AGPL）**：随货单据会被货代/海关接触，AGPL 会带来不必要的许可证义务。**正文只用 ASCII（英文/德文）**：PDF 内置 Base14 字体没有中文字形，写中文会变空白方块（要中文需内嵌 CJK 字体子集，属后续项）。压缩级别设为 0：牺牲几十 KB 体积，换来「排障时能直接在文件里搜单号」 |
| **对象存储（P1-6）** | `service/DocumentStorageService` + `config/MinioConfig` | 对象键 `orders/{orderId}/{kind}-{UTC时间戳}.pdf`：按订单分组（可整体归档/GDPR 删除）、带时间戳（重打留痕，「最新一版」= 键最大者，ISO-8601 的字典序即时间序）；sha256 随对象存元数据（单据是对外凭证，事后要能校验）；**两个客户端** —— 上传/下载走内网端点，预签名走对外端点（签名覆盖 Host，必须用同一个 Host 签） |
| **失败语义（P1-6）** | `DocumentNotFoundException` / `DocumentStorageException` | 分开两类：订单不存在或还没生成过 → **404**（正常业务状态，前端可引导用户点「生成」）；对象存储不可用 → **503**（依赖故障，可重试）。一律 200 + success=false 会让前端无法区分这两种情況 |
| **外部平台客户端（P1-4）** | `order-service` `spapi/SpApiOrderClient` + `LwaTokenClient` + `SpApiOrderMapper` | 三步真实契约：`POST {token-url}`（LWA 表单换 access_token，进程内缓存 + 提前 60s 失效）→ `GET /orders/v0/orders`（`NextToken` 分页 + `max-pages` 防不收敛）→ `GET /orders/v0/orders/{id}/orderItems`（补商品名，失败不影响主流程）。`CreatedAfter` 为 ISO-8601 UTC；鉴权头 `x-amz-access-token`。**显式 connect/read 超时**：默认的「无限等待」会让平台僵死时线程被拖住，熔断器救不了被占住的线程 |
| **失败分类（P1-4 × P1-2）** | `PlatformUnavailableException` / `RateLimitedException` / `SpApiClientException` | **重试语义就写在这条继承线上**：429（子类，带 `Retry-After`）/ 5xx / 超时 → `PlatformUnavailableException` 家族，命中 `retry-exceptions` → 交给 P1-2 重试熔断降级；401 → 刷一次令牌再试；其他 4xx → `SpApiClientException`（**不在可重试家族里**，避免重试打光配额）。分错这一刀，要么该重试的不重试，要么不该重试的狂重试 |
| **契约测试（P1-4）** | `SpApiOrderClientContractTest` + `SpApiProbeControllerTest` + `SpApiRetryClassificationTest` | 用 WireMock 起在**随机端口**上做真实 HTTP（不是 Mockito），断言路径/查询参数/鉴权头与「状态码 → 异常类」映射；再用真实 `RetryConfig.getExceptionPredicate()` 锁死「429 子类命中重试配置」——避免 spapi 包与 yml 两处各自漂移 |
| 契约桩容器（P1-4） | `wiremock/mappings/*.json` + compose 的 `aslp_wiremock` | 桩以**文件**版本化（跟 `monitoring/` 同一思路：契约是要进代码评审的资产）；靠 `MarketplaceIds` 查询参数分流，于是「正常/限流/超时/5xx/4xx/空结果」共用同一个端点，端到端断言无需为每种故障重启容器。**P1-9 又加了 8 个追踪桩**（DHL 妥投/异常态/查无此单/429/503/超时 + DPD 在途/查无此单），共 19 个 |
| **诊断探针（P1-4）** | `POST /api/orders/spapi/probe` | **无副作用**：只跑客户端 + 映射器，不落库、不写指标、不经熔断器 → 可随时体检「外部契约是否还通」，且不会因为探针自身失败把熔断器推向打开 |
| **尾程追踪（P1-9）** | `route-service` `TrackingService` + `DhlTrackingClient` / `DpdTrackingClient` + `TrackingStatusMapper` | **第三处真实外部契约**（前两处：SP-API、MinIO）。单号长度/前缀做**启发式**识别承运商，**判不出来就要求显式指定、绝不猜**（猜错会让客服在错误的承运商上反复核对单号）。两个承运商报文 → **归一化状态**（`ShipmentState`：CREATED / PICKED_UP / IN_TRANSIT / OUT_FOR_DELIVERY / DELIVERED / EXCEPTION / **UNKNOWN**）—— 归一化只做一次、做在服务端，前端不必为每家承运商各写一套判断；**没见过的状态码落到 UNKNOWN 而不是猜**，原始状态码始终随响应返回。映射顺序有讲究：异常先于妥投判定（`"Delivery attempt failed"` 里含 `deliver`），`pre-transit` 先于 `IN_TRANSIT`（前者含 `transit`）—— 这两条都是被测试逼出来的 |
| **追踪失败语义（P1-9）** | `TrackingNotFoundException` / `TrackingUnavailableException` / `TrackingClientException` | 三类失败**必须分开**，因为调用方的动作不同：查无此单 → **404**（前端说"核对单号"，缓存不记它，因为包裹随时可能被揽收）；承运商不可用（5xx/429/超时）→ **503**（稍后重试；限流额外带 `retryAfterSeconds`，前端才能退避）；承运商拒绝（其他 4xx）→ **502**（可能是我们请求/凭据的问题，返回 400 会误导用户去改一个正确单号）。含**读超时**：JDK 默认读超时是"无限等待"，承运商僵死会拖住线程池，因此显式配 2s/5s |
| **追踪缓存（P1-9）** | `TrackingService` 内有界 LRU + `aslp.tracking.cache-ttl` | 承运商接口有配额、包裹状态变化慢（分钟级）→ 同单号 60s 内复用；**只缓存成功结果**（"查无此单"是会过期的事实，缓存它会让用户持续看到错的结论）；命中时标记 `stale=true`（不谎称"刚问来的"）；`?refresh=true` 强制穿透。**有界**（LRU + `max-cache-entries`）：无界缓存就是内存泄漏。端到端用 WireMock **请求计数**证明"3 次查询只打上游 1 次"——缓存最容易变成一句空话 |
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
| 邮件节流窗口（P1-10） | Redis（`spring.data.redis.host=localhost`）里的 `inventory:warning:last-mail-at`，TTL = `mail-interval` | Redis = `aslp_redis`。**这条键是「多实例/重启后仍然节流」的证据**：`docker exec aslp_redis redis-cli ttl inventory:warning:last-mail-at` 应返回 0 < TTL ≤ 1800 |
| 对象存储（P1-6） | `aslp.minio.*`：endpoint=`http://localhost:9000`（compose 映射），`region=us-east-1`，桶 `aslp-documents`，预签名 15m | endpoint=`http://aslp-minio:9000`（服务别名）+ **`public-endpoint=http://localhost:9000`**：预签名 URL 必须用对外端点签（签名覆盖 Host，用内网端点签出来的浏览器打不开）；`spring.datasource` 之外的依赖均就绪后 `order-service` 才启动（compose `depends_on: service_healthy`） |
| 单据抬头（P1-6） | `aslp.document.*`：发货方名称/地址/税号、目的国、申报价值、HS 编码、币种 | 同左（演示值，非真实主体）。**改抬头/调申报价值不需要重新构建镜像** |
| 邮件健康探测 | 见上行「邮件（P1-5）」 | docker profile 已恢复开启（`aslp_mailhog` 存在） |
| 指标暴露与抓取（P1-1） | `management.endpoints.web.exposure.include` 含 `prometheus,metrics`；`management.metrics.tags.application=${spring.application.name}` | **docker profile 必须同时写**：profile 里的 `include` 会整体覆盖基础 profile，漏写就抓不到（已踩过）。Prometheus 抓取目标用连字符别名（下划线主机名会让 Tomcat 返回 400，见 §9 #30） |
| 追踪（P1-9） | `aslp.tracking.*`：`mock-enabled=true`（默认，无凭据可演示）、DHL/DPD 真实端点、凭据经 `DHL_API_KEY`/`DPD_API_KEY` 注入、`cache-ttl=60s` | `mock-enabled=false` + 端点指向 `http://aslp-wiremock:8080`（fake 凭据）+ **`read-timeout=1s`**（故意短于超时桩的 3s，用来证明超时真的生效）。本地没起 WireMock 时追踪不影响其他功能（默认走 Mock） |
| 监控栈（P1-1） | `aslp_prometheus:9090`（保留 7 天）/ `aslp_grafana:3000`（匿名只读） | Grafana 管理密码经 `GF_SECURITY_ADMIN_PASSWORD` 注入（默认 `aslp-admin`，仅演示） |
| 链路追踪（P1-3） | `management.tracing.sampling.probability=1.0`（全量采样）；`management.zipkin.tracing.endpoint=http://localhost:9411/api/v2/spans` | docker profile 必须把 endpoint 改指向 `http://aslp-zipkin:9411/api/v2/spans`：**下划线主机名会让 java.net.URI 解析不出 host**，WebFlux 网关上报时报 `Host is not specified`（见 §9 #35）。采样率 1.0 只适合演示，生产按流量调到 0.1 级别 |
| 日志采集（P1-1b） | 无（业务代码不感知）；Promtail 侧 `-config.file` 与 `docker_sd_configs.host=unix:///var/run/docker.sock` | Loki 侧 `limits_config.ingestion_rate_mb` 演示环境放宽到 16MB/s（避免丢日志）。新增服务的日志会自动被采集（按容器名前缀 `aslp_` 过滤），无需改配置 |
| 平台接入（P1-4） | `aslp.order.amazon.*`：真实端点 `sellingpartnerapi-eu.amazon.com` + `api.amazon.com/auth/o2/token`，凭据从环境变量注入（`AMAZON_LWA_CLIENT_ID` / `_CLIENT_SECRET` / `_REFRESH_TOKEN`，**不写进仓库**）；`read-timeout=5s`、`max-pages=5` | 同一套配置但 endpoint/token-url 指向 `http://aslp-wiremock:8080`（桩用的假凭据，非密钥）、`read-timeout=1s`（**故意短于超时桩的 3s 延迟**，用来证明读超时真的生效） |
| 契约桩（P1-4） | 无（本地跑单测时用进程内 WireMock，不需要容器） | `aslp_wiremock`（`:8099` 映射到容器 8080）；桩映射只读挂载 `./wiremock:/home/wiremock:ro`；`order-service` 经连字符别名 `aslp-wiremock` 寻址（下划线陷阱见 §9 #21/#30/#35） |
| 仓库映射（P1-4） | `aslp.order.amazon.default-warehouse=Bruchsal` + `warehouse-by-city`（城市→履约仓，大小写无关） | 同左。**口径放在配置里而不是代码里**：开新仓不用改代码；未命中城市落到默认仓（不留空仓库，否则拣货任务无从下手） |
| 报表数据源（P1-7） | `aslp.report.order-base-url=http://localhost:8081` / `inventory-base-url=http://localhost:8082`；`connect-timeout=2s` / `read-timeout=3s`；`low-stock-limit=20` | 同左但换成**连字符别名**（`http://aslp-order-service:8081` / `http://aslp-inventory-service:8082`，下划线主机名非法见 §9 #21/#30/#35）。超时必须显式给上限：看板是"随时会被点开"的接口，默认无限读超时会被一个卡住的上游占满线程。**该服务刻意不注册任何"下游探活"健康组件** —— 上游抖动应体现为响应里的 degraded，而不是把本容器判成 DOWN 让编排系统反复重启一个其实正常的服务 |

> 生产部署前必须通过 `ASLP_JWT_SECRET` 注入强随机密钥，并替换 compose 中的 `POSTGRES_PASSWORD`。

---

## 8. 验证清单（2026-09-17 实测）

- [x] `mvn clean package -T 1C` — **BUILD SUCCESS**，6 个模块全部产出可执行 fat jar
- [x] 单元测试 **256 个全部通过（0 跳过、0 失败）**：gateway 9 / order 89 / inventory 45 / route 94 / auth 5 / report 14
- [x] `bash scripts/container-verify.sh`（先 `docker compose down -v` 冷启动）— **EXIT=0**：构建 6 个服务镜像 → 启动 → **18/18 容器就绪**（含 Prometheus / Grafana / Zipkin / Loki / Promtail / WireMock 桩 / MailHog / MinIO）→ 端到端断言 **124/124 通过** → **6/6 外部化状态验证**：6a 重启 order-service 后状态机仍为 FBA_RELABELED（DB）；6b 重启 inventory-service 后 `force=false` 仍 `mailSkipped=true` 且 Redis 里 TTL>0（Redis）
- [x] **VRP 引擎（P1-2b）**：求解演示问题得 1 条路线 / 4 个停靠点 / 615.1 km；单作业往返 Bruchsal→Karlsruhe 精确等于 **38.6 km**（几何锁定，同时证明单位是 km 而非「度」）；运力不足返回 200 + `feasible:false` + 未指派作业列表；缺 `deliveries` 返回 400
- [x] **指标可观测性（P1-1）**：`/actuator/prometheus` 输出约 200KB 指标（含 `application` 标签与业务指标）；Prometheus **7/7 抓取目标 healthy**（6 业务服务 + 自身）；`aslp_vrp_distance_count` 等业务指标可从 TSDB 查到；Grafana `database ok` 且看板 `aslp-overview` 已自动加载；P95 经 `histogram_quantile` 实测可得（如 route-service 0.063s）
- [x] **链路追踪（P1-3）**：6 个服务均上报 span（含 WebFlux 网关）；**跨服务同一 traceId 实测 23 条**（同一条 trace 里同时含 gateway 与 order-service 的 span）；业务 span `aslp.vrp.solve` 带 `stopCount` / `totalDistanceKm` 等业务标签；日志已带 `[traceId-spanId]`（MDC 生效）；且**未出现指标重复计时**（一次拉取 → `aslp_order_pull_seconds_count` = 1）
- [x] **日志聚合（P1-1b）**：Promtail 累计读取容器日志 **2000+ 条、解析错误 0**；Loki 可查到 `{project="aslp"}` 的日志流；采样 80~93 行日志中均带 traceId（可一键跳 Zipkin）；Grafana Loki 数据源连通（uid=aslp-loki）
- [x] **平台契约（P1-4）**：容器内探针实测 `pages=2 / count=3`（**WireMock 请求计数确认订单列表接口恰好被调 2 次** = 真正翻了页）；商品名来自 `/orderItems`；城市 → 履约仓映射正确；缺收货城市的订单自动打 `ADDRESS_INVALID`；明细接口 500 时商品名回退占位符；**429 → `RateLimitedException`（retryAfter=7）/ 503 → `PlatformUnavailableException` / 读超时 → `elapsedMs=1005`（= 配置的 1s，没等到桩的 3s）/ 400 → `SpApiClientException`（retryable=false）**；空结果 `ok=true,count=0` 不判失败
- [x] **邮件真实化（P1-5）**：`POST /api/inventory/warnings/trigger` 实测 `mailSent=true`（阈值 10）；MailHog 收件箱收到邮件，**解码后**主题=`[库存补货建议] AMZ-9999@Mönchengladbach 剩余 5（阈值 10）`，HTML 正文 5591 字符（含 `<table>` 行内样式表格）、建议补货量 15 与 SKU/仓库均正确；邮件为 `multipart/alternative`（纯文本兜底 + HTML）；`force=false` 再触发返回 `mailSkipped=true`（**邮件节流在容器里真的生效**）；`/actuator/health` 的 `mail` 组件为 UP
- [x] **单据对象存储（P1-6）**：`POST .../documents/shipping-label` 返回对象键 `orders/AMZ-1001/shipping-label-<UTC>.pdf` + 64 位 sha256；**下载得到 3771 字节的合法 PDF**（落盘于 `target/smoke-logs/p1-6-label-e2e.pdf` 可人工打开）；报关单走不同对象键；列举可见多个版本（重打留痕）；**预签名 URL 用对外端点签，宿主直接下载成功**；白名单外的类型返回 400；`/actuator/health` 的 `minio` 组件 UP 且 details 带桶名与失败原因
- [x] Flyway 迁移：`flyway_schema_history_order` / `flyway_schema_history_inventory` + `orders` / `inventory` 四表均由迁移脚本创建，`ddl-auto=validate` 校验通过
- [x] **尾程追踪（P1-9）**：容器内 `GET /api/routes/tracking/00340434161094000000`（20 位 → 自动识别 DHL）实测 `state=DELIVERED` + 保留原始码 `carrierStatus=delivered`；14 位单号自动识别为 DPD 且 `state=IN_TRANSIT`；异常态桩正确归一化为 `EXCEPTION`（**异常优先于妥投判定**）；**同一单号 3 次查询只打上游 1 次**（WireMock 按单号精确计数），`refresh=true` 后计数变 2；查无此单 404、429 → 503 + `retryAfterSeconds=7`、5xx → 503、**读超时（桩 3s > 容器 1s）→ 503 且未等满**、无法识别单号 400、非法 carrier 400
- [x] 订单幂等导入：重复 `POST /api/orders/pull` 只更新不新增
- [x] 状态机按订单号隔离：A 订单推进不影响 B 订单
- [x] 匿名访问 `/bff/orders/search` 返回 401、携带 JWT 返回 200（docker profile 鉴权生效）
- [x] `docker compose config` — 全部服务配置有效

---

## 9. 已修复的阻塞性问题（累计 56 项）

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
| 48 | **报表单测编译失败**：`thenReturn(List.of(new Object[]{...}))` 报「找不到合适的方法」（P1-7 新增） | `List.of(T...)` 的变参推断把 `Object[]{a,b}` 当成**变参展开**，于是推断出 `List<Object>` 而不是 `List<Object[]>`，与 mock 的返回类型 `List<Object[]>` 不符 | 显式写类型见证 `List.<Object[]>of(...)`。类型推断失败时的第一反应应该是"我有没有把意图写清楚"，而不是怀疑编译器 |
| 49 | **自己新写的断言第一次跑就假失败**（P1-7）：`expected: <2> but was: <1>` | 测试数据与断言在同一屏内仍然对不上：`byStatus` 里 `CREATED=1` / `PAID=2`，断言却写了 `get("CREATED") == 2` | 两个键都断言上（1 和 2）。**"只断言一个键"很容易掩盖分布整体错误**，写分布类断言时应覆盖每个键 || 50 | **状态机状态只在内存里**（P1-8）：`DEMO-001` 推进到 `FBA_RELABELED` 后重启 order-service → 状态回到 `CREATED`；多实例部署时各实例看到的还不一样 | 缺陷 #16 只修了“实例被跨订单共享”，但隔离后的实例仍全在进程内的 `ConcurrentHashMap` 里 —— **“隔离”不等于“持久化”** | DB 成为唯一真相源：`order_state`（当前状态 + `@Version` 乐观锁）+ `order_state_event`（只追加事件轨迹），每个请求读库→判定→同事务写回+记事件；进程内不再保存状态。新增 `GET /{orderId}/history` 审计接口，并在 `container-verify.sh` 加 **6/6 重启验证**（重启后仍为 FBA_RELABELED，内存实现必失败） |
| 51 | **重置一个“从未推进过”的订单会返回 500**（P1-8 代码评审时发现，尚未进入端到端即修掉） | 用 `stateRepository.deleteById(id)` 实现 reset，而 **Spring Data JPA 3.x 的 `deleteById` 在目标不存在时抛 `EmptyResultDataAccessException`**（早期版本是静默忽略 —— 这是个容易按旧印象写错的 API 行为） | 改为 `findById(id).ifPresent(stateRepository::delete)`，并补 `resetOnUntouchedOrderIsIdempotent` 测试。运维/夹具复位类接口**必须幂等**：重复调用是正常使用方式，不是错误 |
| 52 | **`pre-transit` 被归一化成 `IN_TRANSIT`**（P1-9）："承运商还没收到包裹"显示成"运输途中"，客户会以为包裹已经在路上 | `fromDhl("pre-transit", ...)` 的匹配串含 "transit"（pre-**transit**），而 CREATED 关键词的判定排在 IN_TRANSIT **之后** | 把 CREATED 判定提到 IN_TRANSIT 之前；同类陷阱还有 `Delivery attempt failed` 含 `deliver`（异常必须先于妥投判定）。两条都写进注释与测试 |
| 53 | **缓存过期测试随机失败**（P1-9） | 用 `cache-ttl=1ms` 验证过期，而 `Instant.now()` 是微秒精度 —— 两次调用常落在同一毫秒内，过期判定“还没来得及”生效 | 改为 20ms TTL + sleep 50ms。**时间相关行为要让它真的过去一会儿**：1ms 这种“理论上够用”的值在真实时钟面前就是随机数 |
| 54 | **新增依赖后既有 `@WebMvcTest` 切片 6 例全部 `APPLICATION FAILED TO START`**（P1-9） | 切片只装配 Web 层，Controller 新增的 `TrackingService` 依赖必须在测试里 `@MockBean`，否则上下文起不来。**编译期完全看不出**，只有跑测试才发现 | 补 `@MockBean TrackingService`。经验：给 Controller **加构造参数**时，顺手搜一下它的切片测试 |
| 55 | **降级期间的「还要等多久」会骗人**（P1-10，测试逼出来的）：Redis 抖动降级到进程内节流后，`secondsUntilAllowed()` 仍返回 0——而实际上窗口还在拦 | 该方法只读 Redis 的 `remainTimeToLive()`；降级时 Redis 侧确实没有窗口，但**进程内窗口才是真正生效的那个** | 改为取两者较大值：**「实际生效的节流 = 两个窗口里更严的那个」**。诊断类接口在降级路径上尤其不能报乐观值 |
| 56 | 自己新写的测试 `@DisplayName` 里嵌套了半角双引号 → 编译报 `需要')'`（P1-10） | Java 字符串中的半角引号必须转义；中文全角引号「」才能安全嵌套 | 统一改用「」。教训：**给 `@DisplayName` 写中文时，引号一律用全角** |
---

## 10. 当前限制与后续路线

### 限制
- **注册中心缺失**：网关使用直连 URI（`lb://` 已移除）。引入 Eureka/Nacos 后应改回服务发现 + `lb://`。
- **无日志保留策略**：Loki 单机文件系统存储，未设保留期与容量上限；生产应配 `retention_period` + 对象存储，并按合规要求定保留时长。
- **追踪采样为全量**：`management.tracing.sampling.probability=1.0` 仅适合演示；生产需下调（如 0.1）并换成 Elasticsearch 存储（当前 Zipkin 用内存存储，重启即清）。
- **观测端点未鉴权**：`/actuator/prometheus` 在 docker profile 下放行（网关与 auth-service 均放行），仅靠网络隔离。生产建议改用独立 management 端口 + 来源限制/双向 TLS。
- **Grafana 为匿名只读演示态**：`GF_AUTH_ANONYMOUS_ENABLED=true`，admin 密码为默认值；生产必须关掉匿名并接入统一认证。
- ~~无邮件服务器~~ ✅ **已解决（P1-5）**：容器内 `aslp_mailhog` 提供真实 SMTP（`:1025`）与 Web 收件箱（`:8025`），邮件为 Thymeleaf 模板渲染的 multipart/alternative。剩余限制见下文"邮件仅到收得下"。
- **报表看板是只读聚合，不是数据仓库**：`report-service` 的每个指标都由上游实时计算（不落库、不缓存），因此看板口径 = 上游口径。数据量长大后需要预聚合/物化视图，否则每次打开看板都会打一轮全表 `group by`；当前订单量下（百级）尚可。
- **报表看板只覆盖订单与库存**：VRP 里程、运费结算等 M3 指标尚未接入看板（route-service 的可观测数据目前只到 Prometheus，看板要图表化需再补一个上游契约）。
- **状态机事件表无归档策略**：`order_state_event` 只追加（这是审计表该有的性质），但没有保留期与归档；长期运行需要按合规要求定保留时长并定期导出。
- **并发推进同一订单时返回 500 而非 409**：`order_state` 的 `@Version` 乐观锁会抛 `ObjectOptimisticLockingFailureException`，当前没有把它映射成 `409 Conflict`。语义上「你的操作基于过期状态」正是 409，属小改动但未做（前端目前只能拿到 500 并重试）。
- **状态机与履约流水线是两套状态**：`OrderRecord.status`（平台拉取/客服修正驱动，自由字符串）与 `order_state.current_state`（换标工单，枚举 + 状态机）没有打通 —— 演示用的 `DEMO-001` 在 `orders` 表里并不存在。真实系统需要定义两者的边界（例如"订单发货"事件同时推进两边），当前刻意不合并以免演示数据污染真实订单。
- **安全演示态**：`auth-service` 未接入用户表，按用户名推导角色；JWT 为对称密钥，网关侧 JWKS 端点为占位。
- **未接入真实平台**：Amazon SP-API 客户端已是**真实 HTTP 实现**（P1-4），但**未用生产卖家账号实战验过**（LWA 正式授权流程、SP-API 的签名/限流配额、真实报文字段都可能有出入）；eBay 仍无实现（只有 Mock）。
- **商品明细是 N+1 调用**：SP-API 订单列表不含商品名，只能逐单查 `/orderItems`（当前串行，单页 50 单就是 50 次调用）。仅串行 + 单次拉取尚可，规模化前应改并行（有界并发）或改为按需懒加载；`aslp.order.amazon.fetch-item-titles=false` 可先关掉。
- **探针无额外鉴权**：`POST /api/orders/spapi/probe` 只靠网关 JWT 保护，且能触发对外调用；生产应加管理员角色限制 + 频率限制（它毕竟是一个“可以打外部平台”的入口）。
- **邮件仅到“收得下”**：MailHog 是**开发/演示用**收件箱（不转发、无 TLS、镜像仅 amd64，Apple Silicon 上走模拟）；生产需接真实 SMTP/邮件服务（如 SES/Postmark）并配 SPF/DKIM。收件人目前是**全局单值**配置（真实业务需按仓/品类路由到不同采购负责人）。
- **邮件只能单实例节流**：节流窗口在 Redis（`inventory:warning:last-mail-at`，`SET NX + TTL`），多实例安全；但 **Redis 不可用时会降级为进程内节流**（单实例仍严格 30 分钟一封，多实例会放宽到每实例一封）—— 这是刻意的取舍：邮件是运维告警通道，既不能因节流组件故障而轰炸，也不能静默失联。详见 `RedisMailThrottle` 类注释
- **单据无中文/无条码**：PDF 用内置 Helvetica/Courier，只能出英文/德文（中文字形需内嵌 CJK 字体子集，会让镜像变大）；单号目前是等宽大字而不是真条码（需引入 ZXing 生成 Code128）。
- **MinIO 为单节点演示形态**：无擦除码/多节点、无生命周期与保留策略、无 TLS，演示凭据写在 compose 里（生产须外置密钥 + 开启服务端加密）。
- **单据不会自动清理**：每次重打都新增一个版本（为了留痕），当前没有保留期/归档策略，长期运行需要配对象生命周期或定期扫导。
- **面单缺收货地址**：平台订单的收货地址未入库（`orders` 表只存了履约仓），因此面单打印的是「仓内作业联」+ 目的地国家；真实面单需补齐收货地址（或直接对接承运商取面单）。
- **追踪未用生产凭据实战验证**：DHL 用公开文档里的 Unified Tracking 结构、DPD 按公开的 `parcelLifeCycle` 结构建模（DPD 正式接入走 Web API + OAuth），且**未用真实商户凭据联调过**；契约桩锁住的是「我们理解的契约」。真接入时以实际报文为准并同步更新 `dto/*` 与 `wiremock/mappings/*`（与 P1-4 对 SP-API 的处理同一口径）。
- **追踪不落地、不推送**：只做「查一次返回一次」，没有把节点变更写入本地（因此无法做“包裹卡住 3 天”这类主动告警）。生产需要的是定时轮询或承运商 Webhook + 事件表（属 P2/P3 范围）。
- **单号识别是启发式**：靠长度/前缀区分 DHL 与 DPD，行业惯例而非协议保证；已在 API 上保留显式 `carrier` 参数兜底，判不出来就 400（不猜）。
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
| `scripts/smoke-test.sh` | 端到端冒烟（124 项断言）；`--external` 可直接打已运行的容器 |
| `DEMO.md` | **演示手册**：8 站动线 + 每站命令与台词 + 测试数据速查 + 追问对答 + 现场排障 |
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
| `statemachine/` | `OrderStates` / `OrderEvents` / `SimpleOrderStateMachine`（纯转换规则，可用初始状态构造）/ `OrderStateMachineService`（读库→判定→同事务写回+记事件；`StateTransition` 返回 from/to/accepted）/ 实体 `OrderStateRecord` · `OrderStateEventRecord` + 仓储（P1-8 持久化） |
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
| `service/TrackingService.java` | 承运商识别 + 有界 LRU 缓存 + Mock/真实客户端切换 + 缓存标记（P1-9：从骨架升级为真实实现） |
| `controller/RouteController.java` | `/health`、`POST /optimize`（接入真实引擎；入参错误 400）、`GET /tracking/{number}`（404 / 503 / 502 / 400 的失败语义） |

**`services/report-service/`（M1/M5 报表，:8085）** —— 只读聚合方，不含业务规则：

| 文件 | 职责 |
|---|---|
| `ReportServiceApplication.java` | 入口；**显式 `@EnableConfigurationProperties(ReportProperties.class)`**（漏写不报错，只在启动时炸，见 §9 #43） |
| `config/ReportProperties.java` | `aslp.report.*`：两个下游 baseUrl + 显式超时 + 低库存展示上限 |
| `client/OrderStatsClient.java` | 消费 `GET /api/orders/stats`（订单计数与三个分布） |
| `client/InventoryWarningClient.java` | 消费 `GET /api/inventory/warnings/status`（阈值 + 低库存明细，与补货邮件同源口径） |
| `client/ReportHttp.java` | 复用 Boot 自动装配的 `RestClient.Builder` 并装显式超时（保住 Observation/traceId 透传） |
| `client/ReportSourceUnavailableException.java` | 把"哪个上游挂了 + 原因"包成一种异常，供聚合层统一降级 |
| `dto/OrderStatsSnapshot.java` / `InventoryWarningSnapshot.java` | 上游契约的强类型镜像（`ignoreUnknown=true`，前向兼容）；各带 `unavailable()` 兜底快照 |
| `dto/DashboardReport.java` | 对外聚合根：`degraded` + `unavailable[]` + `orders{}` / `inventory{}`（各含 `available`） + `ChartData{labels,values}` |
| `service/ReportAggregationService.java` | 聚合 + **部分降级** + `Observation` 埋点（`aslp.report.dashboard`） |
| `controller/ReportController.java` | `/health`、`/dashboard`（全量）、`/orders`、`/inventory`（单区块） |
| `resources/static/dashboard.html` | ECharts 看板页（同源托管、15s 自刷新、degraded 徽标、CDN 不可达时降级提示） |

### 13.4 配置怎么读（最容易踩坑的地方）

| 路径 | 含义 |
|---|---|
| `src/main/resources/application.yml` | 默认 profile：面向本机（`localhost` 数据库 / Redis） |
| `src/main/resources/application-docker.yml` | `docker` profile：面向容器（服务名寻址、定时任务与备份开启） |
| `src/main/resources/db/migration/<svc>/V<n>__<desc>.sql` | Flyway 迁移；order 读 `db/migration/order`（V1 订单表 + V2 状态机表），inventory 读 `db/migration/inventory`，两服务共用同一物理库但历史表独立 |

三条血泪教训（均已登记到 §9）：只有 `SPRING_PROFILES_ACTIVE=docker` 才会加载 docker 覆盖；`redisson.singleServerConfig.*` 不会被 starter 绑定（要写 `spring.data.redis.*`）；容器主机名的下划线对 `java.net.URI` 非法（网关路由要用连字符别名）。

### 13.5 测试怎么读

| 类型 | 位置 | 特点 |
|---|---|---|
| 切片测试 `@WebMvcTest` | `*/controller/*Test.java` | 只加载 Web 层，`@MockBean` 掉服务与仓库，断言 HTTP 状态与 JSON |
| 纯单元测试 | `*/service/*Test.java`、`*/engine/*Test.java`、`*/statemachine/*Test.java` | Mockito 驱动，不启动 Spring；库存闭环测试用 Mock 的 `RLock` + 真实业务逻辑；`GeoDistanceTest` / `HaversineCostModelTest` 用真实城市坐标把「纬度在前」的约定钉死 |
| 端到端断言 | `scripts/smoke-test.sh` | 经网关 8080 打真实服务，覆盖路由、鉴权与业务链路；限流用并发突发断言（见 §9 #28），带 JSON body 的断言用 `grep -F`（见 §9 #29）；涉及监控的两项做**有界轮询**（见 §9 #33）；断言一律用 here-string 而非管道（见 §9 #32）；含空格的期望值不要对响应体去空白（见 §9 #39） |
| **契约测试** | `order-service/src/test/java/com/aslp/order/spapi/*ContractTest.java` | 用 WireMock 起在**随机端口**上做真实 HTTP（平台是假的、客户端是真的）：`SpApiOrderClientContractTest` 覆盖 429/超时/5xx/4xx/401 刷新/令牌缓存/分页/空结果/明细失败；`SpApiRetryClassificationTest` 用真实 `RetryConfig` 锁死「异常分类 ↔ 重试配置」一致；`SpApiTestFixture` 是共享夹具（避免各测试各写一套装配） |
| **邮件/P D F 测试** | `ReplenishmentMailServiceTest` / `PdfDocumentWriterTest` / `DocumentStorageServiceTest` | 邮件用**真实 Thymeleaf 引擎**渲染模板（拼错变量名就报错），并用 `writeTo()` 往返序列化后再断言 MIME（见 §9 #41）；PDF 断言文件头/尾 + 明文内容（压缩级别 0）；存储层 Mock MinioClient，只锁对象键/元数据/异常分类 |
| **报表聚合测试（P1-7）** | `report-service` `ReportClientTest` / `ReportAggregationServiceTest` / `ReportControllerTest` | 客户端层用 **JDK 自带 `HttpServer`** 起真服务（验路径/反序列化/502 包装，Mockito 验不到“接线”）；聚合层用 Mockito 专攻**部分降级**语义；控制器层**真启动 Spring 上下文**（上游指向死端口）断言 `degraded=true` + 200，并验证静态页确实被打进 jar |
| **持久化测试（P1-8）** | `order-service` `OrderStateMachineServiceTest`（`@DataJpaTest` + H2） | 状态机持久化**必须真写库**才算验证：`stateSurvivesServiceRestart` 新建一个 service 实例（= 进程内状态归零）读同一个库，断言仍是 `FBA_RELABELED` —— 旧的 `ConcurrentHashMap` 实现会在这里变红。另有「非法转换留痕」「读接口不产生写入」「轨迹顺序」等。H2 建表由实体生成，**关掉 Flyway**（迁移是 PG 方言） |

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
5. `order-service/statemachine/SimpleOrderStateMachine.java` → `OrderStateMachineService.java` —— 纯转换规则 与 “DB 为唯一真相源” 的持久化写法（重启不丢 + 事件审计）
6. `inventory-service/service/InventoryLockService.java` —— 分布式锁 + 乐观锁双层防超卖
7. `report-service/service/ReportAggregationService.java` —— 只读聚合方的“部分降级”取舍（与单据的 503 语义正好相反）
8. `route-service/tracking/TrackingStatusMapper.java` + `TrackingClientContractTest.java` —— 两家承运商异构状态的归一化，以及“契约测试”到底在验什么
9. `scripts/smoke-test.sh` —— 反向检验自己对上面各环节的理解

---
