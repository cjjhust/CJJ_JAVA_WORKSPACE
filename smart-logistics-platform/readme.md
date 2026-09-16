# Smart Logistics Platform (ASLP) — 海外仓智能物流系统

> 最后更新：2026-09-17 ｜ 版本 `1.0.0-SNAPSHOT`
> 状态：**M1–M4 全部交付 + P0 基线补齐（P0-1～P0-5）+ P1-2 限流与容错 + P1-2b VRP 引擎修复 + P1-1 可观测性（Micrometer / Prometheus / Grafana，含业务指标）；`mvn clean package -T 1C` 全绿（117 个单测全部通过，0 跳过），容器全量验证 12/12 healthy + 端到端冒烟 46/46 通过**
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
| 构建 | **Maven 3.9+（“高铁模式” `-T 1C` 并行编译）** |
| 测试 | JUnit 5 + Spring Boot Test + Mockito |
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
# 一键：构建 6 个服务镜像 → 启动基础设施 + 6 服务 + Prometheus + Grafana → 等 healthy → 跑 46 项断言
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
│   ├── smoke-test.sh              # 端到端冒烟测试 46 项断言（支持 --external 打外部服务）
│   └── container-verify.sh        # P0-3 容器全量验证：构建 → 启动 → healthy → 断言
├── monitoring/                    # P1-1 可观测性（全部以文件版本化，容器启动即加载）
│   ├── prometheus/prometheus.yml  # 抓取配置（6 个服务的 /actuator/prometheus）
│   └── grafana/
│       ├── provisioning/          # 数据源（uid=aslp-prometheus）与看板加载器
│       └── dashboards/*.json      # ASLP 总览看板（9 个面板）
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
    │   │                          #        + statemachine(按订单隔离) + strategy(策略模式) + task
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
| **策略模式** | `order-service` `OrderPullStrategy` → `MockAmazonStrategy` / `AmazonSpApiStrategy` | 通过 `aslp.order.mock-enabled` 切换；无真实 SP-API 账号时走 Mock，保证流水线可测 |
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
| **JWT 签发** | `auth-service` `JwtTokenService` | JJWT 0.12.5 HS384，含 `roles` / `scope` 声明，TTL 可配 |
| **定时任务** | `OrderSyncTask`（5 分钟，容器启用）、`InventoryWarningTask`（60s）、`DatabaseBackupTask`（每日 02:00） | 订单流水线式自动导入；安全库存预警 + 补货邮件；`pg_dump` 参数化并可开关 |

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
| 邮件健康探测 | `management.health.mail.enabled=false` | 同上（无 SMTP，避免整服务被判 DOWN） |
| 指标暴露与抓取（P1-1） | `management.endpoints.web.exposure.include` 含 `prometheus,metrics`；`management.metrics.tags.application=${spring.application.name}` | **docker profile 必须同时写**：profile 里的 `include` 会整体覆盖基础 profile，漏写就抓不到（已踩过）。Prometheus 抓取目标用连字符别名（下划线主机名会让 Tomcat 返回 400，见 §9 #30） |
| 监控栈（P1-1） | `aslp_prometheus:9090`（保留 7 天）/ `aslp_grafana:3000`（匿名只读） | Grafana 管理密码经 `GF_SECURITY_ADMIN_PASSWORD` 注入（默认 `aslp-admin`，仅演示） |

> 生产部署前必须通过 `ASLP_JWT_SECRET` 注入强随机密钥，并替换 compose 中的 `POSTGRES_PASSWORD`。

---

## 8. 验证清单（2026-09-17 实测）

- [x] `mvn clean package -T 1C` — **BUILD SUCCESS**，6 个模块全部产出可执行 fat jar
- [x] 单元测试 **117 个全部通过（0 跳过、0 失败）**：gateway 9 / order 31 / inventory 23 / route 48 / auth 5 / report 1
- [x] `bash scripts/container-verify.sh`（先 `docker compose down -v` 冷启动）— **EXIT=0**：构建 6 个服务镜像 → 启动 → **12/12 容器就绪**（含 Prometheus / Grafana）→ 端到端断言 **46/46 通过**
- [x] **VRP 引擎（P1-2b）**：求解演示问题得 1 条路线 / 4 个停靠点 / 615.1 km；单作业往返 Bruchsal→Karlsruhe 精确等于 **38.6 km**（几何锁定，同时证明单位是 km 而非「度」）；运力不足返回 200 + `feasible:false` + 未指派作业列表；缺 `deliveries` 返回 400
- [x] **可观测性（P1-1）**：`/actuator/prometheus` 输出约 200KB 指标（含 `application` 标签与业务指标）；Prometheus **7/7 抓取目标 healthy**（6 业务服务 + 自身）；`aslp_vrp_distance_count` 等业务指标可从 TSDB 查到；Grafana `database ok` 且看板 `aslp-overview` 已自动加载；P95 经 `histogram_quantile` 实测可得（如 route-service 0.063s）
- [x] Flyway 迁移：`flyway_schema_history_order` / `flyway_schema_history_inventory` + `orders` / `inventory` 四表均由迁移脚本创建，`ddl-auto=validate` 校验通过
- [x] 订单幂等导入：重复 `POST /api/orders/pull` 只更新不新增
- [x] 状态机按订单号隔离：A 订单推进不影响 B 订单
- [x] 匿名访问 `/bff/orders/search` 返回 401、携带 JWT 返回 200（docker profile 鉴权生效）
- [x] `docker compose config` — 全部服务配置有效

---

## 9. 已修复的阻塞性问题（累计 34 项）

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

---

## 10. 当前限制与后续路线

### 限制
- **注册中心缺失**：网关使用直连 URI（`lb://` 已移除）。引入 Eureka/Nacos 后应改回服务发现 + `lb://`。
- **MinIO 未启用**：镜像源在部分网络环境不可达，`docker-compose.yml` 中默认注释。
- **无日志聚合**：P1-1 已完成指标栈（Micrometer + Prometheus + Grafana），但 Loki/Promtail 日志聚合尚未启用（镜像需另外拉取），归入 P1-1b。
- **观测端点未鉴权**：`/actuator/prometheus` 在 docker profile 下放行（网关与 auth-service 均放行），仅靠网络隔离。生产建议改用独立 management 端口 + 来源限制/双向 TLS。
- **Grafana 为匿名只读演示态**：`GF_AUTH_ANONYMOUS_ENABLED=true`，admin 密码为默认值；生产必须关掉匿名并接入统一认证。
- **无邮件服务器**：补货邮件走 `localhost:1025`，失败仅告警不阻塞（已关闭健康探测）。
- **安全演示态**：`auth-service` 未接入用户表，按用户名推导角色；JWT 为对称密钥，网关侧 JWKS 端点为占位。
- **未接入真实平台**：Amazon SP-API / eBay API 为策略骨架 + Mock 实现。
- ~~DDL 非版本化~~ ✅ **已解决（P0-4）**：数据库结构由 Flyway 版本化管理（`db/migration/{order,inventory}`），`ddl-auto=validate`。

### 后续路线（详见 `todo.md`）
1. ~~P0-1 `order-service` 持久化~~ ✅ **本轮完成**
2. ~~P0-2 数据库初始化脚本~~ ✅ **本轮完成**
3. ~~P0-3 全量容器验证~~ ✅ **本轮完成**（`container-verify.sh` EXIT=0，10/10 容器 healthy；断言数随后续轮次递增，当前 **41 项**）
4. ~~P0-4 Flyway 版本化迁移~~ ✅ **本轮完成**（`db/migration/{order,inventory}` + `ddl-auto=validate`，替换 `ddl-auto: update`）
5. ~~P0-5 `inventory-service` 库存 CRUD~~ ✅ **本轮完成**（`GET /api/inventory/{sku}` + `POST /api/inventory/release`；扣减→查询→释放 闭环单测 12 个全绿）
6. ~~**P1 M5 可观测性**：Micrometer + Prometheus + Grafana + Loki。~~ ✅ **已完成（P1-1，2026-09-17）**：Micrometer + Prometheus + Grafana（含业务指标与 9 面板看板）；Loki 日志聚合归入 P1-1b
7. ~~**P1 M5 限流熔断**：Resilience4j + Redis 令牌桶。~~ ✅ **已完成（P1-2，2026-09-16）**
8. **P1-1b 日志聚合**：Loki + Promtail（需拉取镜像），接入各服务 stdout 并关联 traceId
9. **P1-3 追踪链路**：Micrometer Tracing + Zipkin（本地已有 `openzipkin/zipkin` 镜像，可直接用）
10. **P2 M6 服务治理**：Eureka/Nacos + Spring Cloud Config，网关恢复 `lb://`。
11. **P2 M6 异步解耦**：Kafka 订单异步流水线 + 死信队列。
12. **P3 M7 生产化**：CI/CD、K8s 清单、SonarQube、Testcontainers 集成测试、安全基线。

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
| Grafana 看板面板全是 **No data** | 确认数据源 uid 仍为 `aslp-prometheus`（看板 JSON 按 uid 引用）；再确认该指标名真实存在：`curl localhost:9090/api/v1/label/__name__/values` |

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
| `scripts/smoke-test.sh` | 端到端冒烟（46 项断言）；`--external` 可直接打已运行的容器 |
| `monitoring/prometheus/prometheus.yml` | P1-1 抓取配置；**targets 用连字符别名**（下划线主机名会被 Tomcat 判 400） |
| `monitoring/grafana/provisioning/**` | 数据源（uid 固定 `aslp-prometheus`）与看板加载器；容器启动自动生效 |
| `monitoring/grafana/dashboards/aslp-overview.json` | ASLP 总览看板（9 面板，PromQL 均已对照真实指标名验证） |
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
| `strategy/` | `OrderPullStrategy` 接口 + `MockAmazonStrategy` / `AmazonSpApiStrategy` + `OrderDto` / `OrderPullResult` |
| `task/OrderSyncTask.java` | 定时流水线式导入（`aslp.order.sync.enabled`） |
| `task/DatabaseBackupTask.java` | `pg_dump` 定时备份，参数化可开关 |

**`services/inventory-service/`（M2，:8082）**

| 文件 | 职责 |
|---|---|
| `entity/InventoryItem.java` | `inventory` 表映射：`availableQty` / `lockedQty` 双状态 + `@Version` |
| `repository/InventoryRepository.java` | `findBySkuAndWarehouseCode`、`findBySku`、低库存查询 `findByAvailableQtyLessThan` |
| `service/InventoryLockService.java` | **并发核心**：Redisson `RLock`（键 `inventory:lock:{sku}:{warehouse}`）+ 乐观锁；扣减（预占）与释放（回退） |
| `dto/InventoryView.java` | 查询响应：SKU 汇总 + 各仓明细（record，天然规避 `Map.of` 的 null 限制） |
| `controller/InventoryController.java` | `/health`、`/deduct`、`GET /{sku}` 查询、`POST /release` 释放 |
| `service/ReplenishmentMailService.java` | 补货邮件（SMTP 不可用时仅告警，不阻塞主流程） |
| `task/InventoryWarningTask.java` | 定时低库存预警，接入补货邮件 |

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
| 端到端断言 | `scripts/smoke-test.sh` | 经网关 8080 打真实服务，覆盖路由、鉴权与业务链路；限流用并发突发断言（见 §9 #28），带 JSON body 的断言用 `grep -F`（见 §9 #29）；涉及监控的两项做**有界轮询**（见 §9 #33）；断言一律用 here-string 而非管道（见 §9 #32） |

### 13.6 可观测性怎么读（P1-1）

四个层次，从上往下看：

| 层次 | 看哪里 | 说明 |
|---|---|---|
| ① 应用埋点 | 各服务 `application.yml` 的 `management.*` + 代码里的 `MeterRegistry` | 配置只决定「暴露什么」；业务指标在 `OrderPullService` / `VrpRouteService` / `InventoryLockService` 中直接写入 |
| ② 抓取 | `monitoring/prometheus/prometheus.yml` | 一个 job（`aslp-services`）扫 6 个服务；targets 必须是连字符别名（§9 #30） |
| ③ 存储与查询 | `http://localhost:9090/targets`、`/graph` | 排查先看 targets 的 `health` 与 `lastError`，再看 PromQL |
| ④ 展示 | `monitoring/grafana/**` → `http://localhost:3000/d/aslp-overview` | 数据源与看板都是文件版本化，改完 JSON 重启 `aslp_grafana` 生效 |

**指标命名约定**：Micrometer 名 `aslp.order.pull.requests` → Prometheus 名 `aslp_order_pull_requests_total`（点转下划线、Counter 加 `_total`、Timer 加 `_seconds`）。在应用里断言用前者，在看板里用后者。

### 13.7 建议的阅读顺序

1. `docker-compose.yml` —— 先看系统长什么样、谁依赖谁
2. `readme.md` §2 / §4 —— 技术栈与接口速查
3. `gateway/.../application-docker.yml` + `security/VpnSecurityConfig.java` —— 流量入口与鉴权
4. `order-service/service/OrderPullService.java` —— 最能体现工程能力的主流程（策略 + 幂等 + 事务）
5. `order-service/statemachine/SimpleOrderStateMachine.java` —— 纯 Java 状态机与按订单号隔离
6. `inventory-service/service/InventoryLockService.java` —— 分布式锁 + 乐观锁双层防超卖
7. `scripts/smoke-test.sh` —— 反向检验自己对上面各环节的理解

---
