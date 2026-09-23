# Smart Logistics Platform (ASLP)

## 海外仓智能物流平台 · Overseas Warehouse Logistics Platform · Intelligente Logistikplattform für Auslandslager

> **中 / EN / DE 三语对照**（每节三语并列）
> 开发全过程、56 项缺陷复盘、代码地图 → [`todo.md`](todo.md)｜现场演示脚本（15 分钟动线）→ [`DEMO.md`](DEMO.md)
>
> 版本 `1.0.0-SNAPSHOT` ｜ 6 个微服务 + 12 个基础设施容器 ｜ **256 个单元测试 / 124 项端到端断言全绿** ｜ 包根 `com.aslp.*`

---

## 0. 先看这三个界面 · Start here: three dashboards · Zuerst: drei Dashboards

> 一句话说明三个界面各自证明什么：**指标**（系统健康与业务趋势）、**调用链**（跨服务是怎么串起来的）、**业务看板**（真实数据长什么样）。

### 0.1 Grafana 运营看板 — <http://localhost:3000/d/aslp-overview>（11 个面板）

| | |
|---|---|
| **中文** | 11 个面板，把 6 个服务的**业务指标与日志**放在同一屏：订单拉取结局（成功 / 失败 / 降级）、VRP 求解耗时与总里程、库存锁操作结果（成功 / 余额不足 / 抢锁失败）、HTTP P95 尾延迟、JVM 指标，以及可筛选服务与级别的**日志面板**。看板与数据源都是**文件版本化**的，`docker compose up` 即自动加载，不靠手工点击配置。 |
| **English** | 11 panels combining **business metrics and logs** of all 6 services on one screen: order-pull outcomes (success / failure / degraded), VRP solve latency and total distance, inventory-lock outcomes (applied / insufficient / lock-contention), HTTP P95 tail latency, JVM metrics, plus a **log panel** filterable by service and level. Dashboards and datasources are **file-versioned**, so `docker compose up` loads them automatically — no manual clicking. |
| **Deutsch** | 11 Panels, die **Geschäftskennzahlen und Logs** aller 6 Dienste auf einem Bildschirm bündeln: Ergebnisse des Bestellabrufs (Erfolg / Fehler / degradiert), Lösungsdauer und Gesamtstrecke der VRP-Optimierung, Ergebnisse der Lager-Sperren (angewendet / Bestand zu niedrig / Sperrenkonflikt), HTTP-P95-Latenz, JVM-Kennzahlen sowie ein **Log-Panel** mit Filter nach Dienst und Stufe. Dashboards und Datenquellen sind **dateiversioniert** – `docker compose up` lädt sie automatisch. |

### 0.2 Zipkin 调用链 — <http://localhost:9411>

| | |
|---|---|
| **中文** | 点开任意一条 trace，就能看到一次请求**跨服务**的完整路径与耗时（例如：网关 → 订单服务 → Amazon SP-API 桩）。所有 6 个服务都上报 span，业务埋点（如 `aslp.vrp.solve`、`aslp.tracking.lookup`）带业务属性；`traceId` 自动进日志，**从日志能一键跳到这条 trace**。 |
| **English** | Open any trace to see the **cross-service** path and latency of a single request (e.g. gateway → order service → Amazon SP-API stub). All 6 services report spans; business spans (`aslp.vrp.solve`, `aslp.tracking.lookup`, …) carry business attributes. The `traceId` is written into every log line, so you can **jump from a log line straight to its trace**. |
| **Deutsch** | Jeder Trace zeigt den **dienstübergreifenden** Pfad und die Latenz einer Anfrage (z. B. Gateway → Bestelldienst → Amazon-SP-API-Stub). Alle 6 Dienste melden Spans; Business-Spans (`aslp.vrp.solve`, `aslp.tracking.lookup`, …) tragen Fachattribute. Die `traceId` steht in jeder Logzeile – **vom Log direkt zum Trace**. |

### 0.3 ECharts 业务看板 — <http://localhost:8085/dashboard.html>（15 秒自刷新）

| | |
|---|---|
| **中文** | 一个可直接演示的**业务大屏**：订单状态分布（饼图）、各履约仓订单量（柱状图，Bruchsal / Mönchengladbach）、低库存 SKU 与**安全库存阈值线**、异常打标待办表。数据来自**真实聚合**（订单服务 `/stats` + 库存服务 `/warnings/status`），不是硬编码假数据；任一上游不可用时不白屏，而是显示"部分降级"并在顶部标注是哪一块数据缺失。 |
| **English** | A ready-to-demo **business dashboard**: order-status distribution (donut), orders per fulfillment warehouse (bars, Bruchsal / Mönchengladbach), low-stock SKUs against the **safety-stock threshold line**, and an error-tag worklist. Data is **really aggregated** from the order service (`/stats`) and inventory service (`/warnings/status`) — not hard-coded mock data. If an upstream is down, the page does not go blank: it marks itself "partially degraded" and shows which block is missing. |
| **Deutsch** | Ein direkt vorführbares **Business-Dashboard**: Verteilung der Bestellstatus (Ringdiagramm), Bestellungen je Lager (Balken, Bruchsal / Mönchengladbach), SKUs unter Mindestbestand mit **Schwellenwertlinie** und eine Arbeitsliste fehlerhafter Bestellungen. Die Daten werden **echt aggregiert** (Bestelldienst `/stats` + Lagerdienst `/warnings/status`) – keine hartkodierten Mock-Daten. Fällt ein Upstream aus, bleibt die Seite nutzbar: sie zeigt „teilweise degradiert" und benennt den fehlenden Block. |

---

## 1. 项目是什么 · What it is · Was es ist

**中文** — 面向德国**海外仓**业务的 B2B 履约中台：Bruchsal 总仓 + Mönchengladbach 分仓，对接 Amazon.de / eBay.de 订单，覆盖"接单 → 库存预占 → 拣货发货 → 尾程派送与追踪 → 计费与报表"的完整链路。系统由 6 个 Spring Boot 微服务 + 12 个基础设施容器组成，一条命令冷启动。

**English** — A B2B fulfillment platform for a German **overseas-warehouse** business: a main warehouse in Bruchsal plus a second site in Mönchengladbach, integrated with Amazon.de / eBay.de and covering the full chain "order intake → stock reservation → picking & shipping → last-mile delivery & tracking → billing & reporting". It consists of 6 Spring Boot microservices plus 12 infrastructure containers, started with a single command.

**Deutsch** — Eine B2B-Fulfillment-Plattform für ein deutsches **Auslandslager**-Geschäft: Hauptlager in Bruchsal plus Zweiglager in Mönchengladbach, angebunden an Amazon.de / eBay.de, mit der vollständigen Kette „Bestelleingang → Bestandsreservierung → Kommissionierung & Versand → Letzte Meile & Sendungsverfolgung → Abrechnung & Reporting". Das System besteht aus 6 Spring-Boot-Microservices und 12 Infrastruktur-Containern und startet mit einem einzigen Befehl.

---

## 2. 要解决的业务问题 · Business problems · Fachliche Problemstellung

**中文** — 上系统之前，这家仓库的日常是：订单靠人工在平台后台核对（同步失败率高）、库存预测用 Excel（大促期间出现过超卖）、门兴仓的配送路线凭司机经验（绕路与运费浪费）、每周手工整理报表给管理层（决策滞后）。

**English** — Before the system, daily work looked like this: orders were reconciled manually in the marketplace back office (high failure rate), stock planning lived in Excel (which led to overselling during peak season), delivery routes from the second warehouse relied on driver experience (detours and wasted freight cost), and weekly reports were compiled by hand (late decisions).

**Deutsch** — Vor dem System sah der Alltag so aus: Bestellungen wurden manuell im Marktplatz-Backend abgeglichen (hohe Fehlerquote), die Bestandsplanung lief in Excel (in der Hochsaison kam es zu Überverkäufen), Touren ab dem Zweiglager beruhten auf Fahrererfahrung (Umwege und vermeidbare Frachtkosten), und Reports wurden wöchentlich per Hand erstellt (Entscheidungen zu spät).

---

## 3. 开发了什么 · What was built · Was gebaut wurde

| 里程碑 | 中文 | English | Deutsch |
|---|---|---|---|
| **M1** 订单统一接入 | 策略模式拉取多平台订单（Amazon SP-API 真实 HTTP 客户端 + LWA 令牌缓存 + `NextToken` 分页）→ DTO 归一化 → **幂等落库** → 异常打标（地址/邮编/匹配失败）→ 客服远程修正接口；认证服务签发 JWT | Strategy-based order pull (real SP-API HTTP client with LWA token cache and `NextToken` paging) → DTO normalisation → **idempotent persistence** → error tagging (address / postcode / match failure) → agent correction API; an auth service issues JWTs | Strategiebasierter Bestellabruf (echter SP-API-Client mit LWA-Token-Cache und `NextToken`-Paginierung) → DTO-Normalisierung → **idempotente Persistenz** → Fehlerkennzeichnung → Korrektur-API für den Kundenservice; ein Auth-Dienst stellt JWTs aus |
| **M2** 多仓库存与补货 | Redisson 分布式锁（锁粒度 `sku + warehouse`）+ JPA `@Version` 乐观锁双层防超卖；可用/锁定双状态与释放流程；安全库存定时巡检 + Thymeleaf 补货邮件（MailHog 收件箱）+ **跨实例邮件节流** | Redisson distributed lock (granularity `sku + warehouse`) plus JPA `@Version` optimistic locking against overselling; available/locked dual state with release flow; scheduled safety-stock scan + Thymeleaf replenishment mail (MailHog inbox) with **cross-instance throttling** | Verteiltes Redisson-Lock (Granularität `sku + warehouse`) plus optimistisches Sperren (`@Version`) gegen Überverkauf; Zustände „verfügbar/gesperrt" mit Freigabe; geplanter Mindestbestandslauf + Thymeleaf-Nachbestellmail (MailHog) mit **instanzübergreifender Drosselung** |
| **M3** 路径优化与追踪 | jsprit VRP 求解（真实 km 的大圆成本模型、固定随机种子可复现、无解降级）；运费规则引擎；**DHL/DPD「一单到底」追踪**（异构状态归一化、有界缓存、四类失败语义） | jsprit VRP solving (great-circle cost model in real km, fixed seed for reproducibility, graceful "no feasible solution"); freight rule engine; **DHL/DPD end-to-end tracking** (heterogeneous status normalisation, bounded cache, four failure semantics) | jsprit-VRP-Optimierung (Großkreis-Kostenmodell in echten km, fester Seed für Reproduzierbarkeit, saubere Degradation ohne Lösung); Frachtkosten-Regelwerk; **Sendungsverfolgung DHL/DPD** (Normalisierung heterogener Status, begrenzter Cache, vier Fehlersemantiken) |
| **M4** BFF 与安全运维 | 网关 BFF 多条件分页查询；居家办公 VPN 接入（WebFlux 安全链 + JWT + `roles` 角色映射）；**FBA 退货换标状态机**（8 状态 / 7 事件，状态持久化在数据库 + 事件审计）；`pg_dump` 定时备份 | Gateway BFF multi-criteria paged query; remote-work VPN access (WebFlux security chain + JWT + `roles` mapping); **FBA return & relabel state machine** (8 states / 7 events, state persisted in the database with an event audit trail); scheduled `pg_dump` backup | Gateway-BFF mit mehrkriterieller Paginierung; VPN-Zugang fürs Homeoffice (WebFlux-Sicherheitskette + JWT + `roles`-Mapping); **Zustandsautomat für FBA-Rückgabe & Umettikettierung** (8 Zustände / 7 Ereignisse, Zustand in der Datenbank persistiert, inkl. Ereignis-Audit); geplantes `pg_dump`-Backup |
| **P1** 工程化补齐 | 可观测性栈（指标/链路/日志）、限流与熔断降级、外部平台契约测试、真实邮件与对象存储（面单/报关单 PDF）、ECharts 报表看板、状态机持久化、DHL/DPD 追踪、节流外置 | Observability stack (metrics / tracing / logs), rate limiting and circuit breaking with degradation, contract tests against external platforms, real mail and object storage (shipping-label / customs PDFs), ECharts reporting dashboard, state-machine persistence, DHL/DPD tracking, externalised throttling | Observability-Stack (Metriken / Tracing / Logs), Rate Limiting und Circuit Breaking mit Degradation, Contract Tests gegen externe Plattformen, echtes Mailing und Objektspeicher (Versandlabel-/Zoll-PDFs), ECharts-Reporting-Dashboard, Persistenz des Zustandsautomaten, DHL/DPD-Tracking, ausgelagerte Drosselung |

---

## 4. 解决了哪些工程问题 · Engineering problems solved · Gelöste technische Probleme

> 这一节是项目的技术含量所在：**每一个问题都有对应的代码、测试与端到端证据**，完整复盘见 [`todo.md`](todo.md) 的缺陷表（56 项）。

| # | 中文 | English | Deutsch |
|---|---|---|---|
| 1 | **超卖**：分布式锁（锁粒度 `sku + warehouse`，两仓互不阻塞）+ `@Version` 乐观锁双层防护；释放路径校验"释放量 ≤ 锁定量"，否则会凭空造出库存 | **Overselling**: distributed lock (granularity `sku + warehouse`, the two sites never block each other) plus `@Version` optimistic locking; the release path rejects "release > locked", which would otherwise create stock out of thin air | **Überverkauf**: verteiltes Lock (Granularität `sku + warehouse`, Lager blockieren sich nicht) plus optimistisches Sperren; die Freigabe prüft „Freigabe ≤ gesperrt", sonst entstünde Bestand aus dem Nichts |
| 2 | **平台 API 限流下的稳定性**：429 通过异常继承线自动命中重试配置（`RateLimitedException extends PlatformUnavailableException`），其他 4xx 天然不重试；网关令牌桶按用户/IP 维度隔离（**SCG 的桶 key 不含 routeId**，不隔离会互相拖累） | **Stability under platform rate limits**: 429 automatically matches the retry policy through the exception inheritance line (`RateLimitedException extends PlatformUnavailableException`) while other 4xx never retry; gateway token buckets are isolated per user/IP (SCG's bucket key **does not contain the routeId**, so unisolated buckets throttle each other) | **Stabilität unter Plattform-Limits**: 429 greift über die Ausnahmen-Hierarchie automatisch in die Retry-Konfiguration (`RateLimitedException extends PlatformUnavailableException`), andere 4xx werden nie wiederholt; Gateway-Token-Buckets sind je Nutzer/IP isoliert (der Bucket-Key von SCG **enthält keine routeId**, sonst drosseln sie sich gegenseitig) |
| 3 | **依赖故障不该伪装成业务失败**：单据（凭证）存储不可用 → **503** + 健康检查 DOWN；报表（只读聚合）→ **200 + `degraded:true`** + 分块标注；追踪 → 查无此单 **404** / 不可用 **503** / 上游拒绝 **502**。分错这一刀，前端只能写"操作失败" | **Dependency failures must not masquerade as business failures**: document (voucher) storage down → **503** and health DOWN; reporting (read-only aggregation) → **200 + `degraded:true`** with per-block flags; tracking → not found **404** / unavailable **503** / upstream rejected **502**. Get this wrong and the UI can only say "operation failed" | **Abhängigkeitsfehler dürfen keine Fachfehler vortäuschen**: Dokumentspeicher (Nachweis) ausgefallen → **503** und Health DOWN; Reporting (reine Leseaggregation) → **200 + `degraded:true`** mit Block-Flags; Tracking → nicht gefunden **404** / nicht verfügbar **503** / Upstream abgelehnt **502**. Sonst kann die UI nur „Vorgang fehlgeschlagen" sagen |
| 4 | **异构外部契约**：三家外部系统（Amazon SP-API、MinIO、DHL/DPD）各一套报文与状态码；DHL/DPD 状态归一化为统一枚举（认不出的码落 `UNKNOWN` 而不是猜），时间戳按承运商本地时区（Europe/Berlin）解析。全部用 **WireMock 契约测试**锁住（客户端是真的、平台是假的） | **Heterogeneous external contracts**: three external systems (Amazon SP-API, MinIO, DHL/DPD) each with their own payloads and status codes; carrier statuses are normalised into one enum (unknown codes become `UNKNOWN` instead of a guess) and timestamps are parsed in the carrier's local time zone (Europe/Berlin). All locked down by **WireMock contract tests** (real client, fake platform) | **Heterogene externe Verträge**: drei Systeme (Amazon SP-API, MinIO, DHL/DPD) mit eigenen Payloads und Statuscodes; Carrier-Status werden in ein Enum normalisiert (unbekannte Codes werden `UNKNOWN` statt geraten), Zeitstempel in der Zeitzone des Carriers (Europe/Berlin). Alles über **WireMock-Contract-Tests** abgesichert (echter Client, fake Plattform) |
| 5 | **状态只在内存里 = 重启即丢**：订单状态机外置到数据库（当前状态 + 只追加事件轨迹 + `@Version`），邮件节流窗口外置到 Redis（`SET NX + TTL`，先占位再发信、失败归还资格）。两处都用**重启容器**做端到端证明 | **In-memory state is lost on restart**: the order state machine moved to the database (current state + append-only event trail + `@Version`), the mail throttle window moved to Redis (`SET NX + TTL`; reserve first, release on failure). Both proven end-to-end by **restarting the container** | **Zustand nur im Speicher geht beim Neustart verloren**: der Zustandsautomat liegt nun in der Datenbank (aktueller Zustand + Append-only-Ereignisprotokoll + `@Version`), das Mail-Drosselfenster in Redis (`SET NX + TTL`; erst reservieren, bei Fehler freigeben). Beides end-to-end bewiesen durch **Container-Neustart** |
| 6 | **可观测性要能回答"为什么"**：低基数进指标（可聚合告警）、高基数进 span（订单号、traceId 绝不进 Prometheus 标签，否则时间序列爆炸）；一份 `Observation` 同时产出 span 与指标；日志带 traceId 可跳链路 | **Observability must answer "why"**: low cardinality goes to metrics (aggregatable alerts), high cardinality to spans (order numbers and traceIds never become Prometheus labels, they would explode the time series); one `Observation` yields both span and metric; logs carry the traceId for jumping into the trace | **Observability muss „warum" beantworten**: niedrige Kardinalität in Metriken (aggregierbare Alarme), hohe Kardinalität in Spans (Bestellnummern/TraceIds nie als Prometheus-Labels – die Zeitreihen würden explodieren); ein `Observation` erzeugt Span und Metrik; Logs tragen die TraceId zum Sprung in den Trace |
| 7 | **限流/超时/缓存不能只是"看起来在工作"**：所有对外调用都有显式 connect/read 超时（JDK 默认读超时是无限等待）；追踪结果用有界 LRU 缓存（命中标记 `stale`，`refresh` 穿透），并用 **WireMock 请求计数**证明"3 次查询只打上游 1 次" | **Rate limits, timeouts and caches must not merely "look like they work"**: every outbound call has explicit connect/read timeouts (the JDK default read timeout waits forever); tracking results use a bounded LRU cache (hits marked `stale`, `refresh` bypasses it) and **WireMock request counting** proves "3 lookups, 1 upstream call" | **Limits, Timeouts und Caches dürfen nicht nur „so aussehen"**: jeder ausgehende Aufruf hat explizite Connect-/Read-Timeouts (JDK-Standard wartet unbegrenzt); Tracking nutzt einen begrenzten LRU-Cache (Treffer als `stale` markiert, `refresh` umgeht ihn); **WireMock-Request-Zählung** beweist „3 Abfragen, 1 Upstream-Aufruf" |
| 8 | **工程纪律**：改一处配置就要真跑一次容器冷启动（`@ConfigurationProperties` 忘注册、`@Scheduled` 写 `60s` 这类问题编译期与单测都发现不了）；容器名/主机名一律用连字符（下划线对 RFC 2396/1123 非法，会在 URI 解析、Tomcat Host 校验、Prometheus 抓取三处爆掉） | **Engineering discipline**: every config change is validated by a real cold start (missing `@ConfigurationProperties` registration or a `60s` `@Scheduled` value passes compilation *and* unit tests, yet fails at startup); container/host names always use hyphens (underscores are illegal per RFC 2396/1123 and break URI parsing, Tomcat host validation and Prometheus scraping) | **Ingenieursdisziplin**: jede Konfigurationsänderung wird durch einen echten Kaltstart geprüft (fehlende `@ConfigurationProperties`-Registrierung oder `60s` in `@Scheduled` bestehen Kompilierung *und* Unit-Tests, scheitern aber beim Start); Container-/Hostnamen immer mit Bindestrich (Unterstriche verstoßen gegen RFC 2396/1123 und brechen URI-Parsing, Tomcat-Host-Prüfung und Prometheus-Scraping) |

---

## 5. 技术栈 · Tech stack · Technologie-Stack

| 层次 Layer / Schicht | 选型 Technology / Technologie |
|---|---|
| 语言 / 运行时 | Java 21 LTS |
| 框架 | Spring Boot 3.3.0 · Spring Cloud 2023.0.3 · Spring Cloud Gateway (WebFlux) |
| 安全 | Spring Security 6 + OAuth2 Resource Server + JJWT 0.12.5（RBAC） |
| 数据 | PostgreSQL 16 · Hibernate 6.5 · Flyway（版本化迁移）· Redisson 3.27（分布式锁） |
| 缓存 / 消息 | Redis 7 · Apache Kafka 7.6（ZooKeeper 模式） |
| 算法 | jsprit 1.8（VRP，配合自研 Haversine km 成本模型） |
| 可观测性 | Micrometer + Prometheus 2.54 + Grafana 11.3 + Zipkin 3（Brave）+ Promtail 3 + Loki 3 |
| 外部集成 | Amazon SP-API（`RestClient` + LWA）· MinIO SDK 8.5 + OpenPDF 2.0（面单/报关单）· DHL Unified Tracking / DPD |
| 测试 | JUnit 5 · Mockito · **WireMock 3.9（契约测试）** |
| 邮件 | MailHog 1.0.1（真实 SMTP + Web 收件箱）· Thymeleaf 3（HTML 模板，multipart/alternative） |
| 构建 / 容器 | Maven 3.9+（`-T 1C` 并行）· Docker Compose（容器名 `aslp_*`，网络 `aslp_net`） |

> **为什么用 Maven 而不是 Gradle（三语摘要）** — 中文：核心物流系统承载订单、运费与客户资产数据，需要**可重复构建**与依赖可预测性，`pom.xml` 的声明式静态特性更符合德国本地信息安全审计要求；用一点编译时间换取软件供应链安全。｜English: A core logistics system carries orders, freight charges and customer assets, so **reproducible builds** and dependency predictability matter; a declarative `pom.xml` fits German information-security audits better. We trade a little compile time for supply-chain safety.｜Deutsch: Ein Kernlogistiksystem verarbeitet Bestellungen, Frachtkosten und Kundendaten – **reproduzierbare Builds** und vorhersagbare Abhängigkeiten sind entscheidend; ein deklaratives `pom.xml` passt besser zu deutschen IT-Sicherheitsaudits. Etwas Kompilierzeit gegen Lieferketten-Sicherheit.

---

## 6. 快速启动 · Quick start · Schnellstart

```bash
# 1) 构建 + 全部单元测试  |  build + all unit tests  |  Build + alle Unit-Tests
mvn clean package -T 1C                      # → 256 tests green

# 2) 容器全量启动 + 全量验证  |  full container verification  |  vollständige Container-Verifikation
docker compose down -v                       # 从干净状态开始 / clean state / sauberer Zustand
bash scripts/container-verify.sh             # → 18/18 containers, 124/124 assertions
                                             #    + restart proofs (state & throttle survive restart)
```

本地开发（不起全部容器）｜ Local development ｜ Lokale Entwicklung：

```bash
docker compose up -d aslp_postgres aslp_redis   # 仅基础设施 / infrastructure only
bash scripts/smoke-test.sh                      # 启 6 个服务 → 断言 → 自动清理
```

**期望输出 / Expected output / Erwartete Ausgabe**

```
========================================
  通过: 124   失败: 0
========================================
✅ 状态已持久化（FBA_RELABELED 渡过了服务重启；内存实现会回到 CREATED）
✅ 邮件节流窗口已外部化（重启后仍 mailSkipped=true，Redis TTL=1773s）
```

---

## 7. 服务与端口 · Services & ports · Dienste und Ports

| 服务 Service / Dienst | 端口 | 网关路由 Route | 职责（中 / EN / DE） |
|---|---|---|---|
| `gateway` | 8080 | — | 网关 + BFF + 限流 + JWT 鉴权 ｜ edge routing + BFF + rate limiting + JWT ｜ Edge-Routing + BFF + Rate Limiting + JWT |
| `order-service` | 8081 | `/api/orders/**` · `/bff/**` | 订单统一接入、SP-API 契约、状态机、单据 PDF ｜ order intake, SP-API contract, state machine, document PDFs ｜ Bestelleingang, SP-API-Vertrag, Zustandsautomat, Dokument-PDFs |
| `inventory-service` | 8082 | `/api/inventory/**` | 多仓库存、分布式锁、补货邮件 ｜ multi-site stock, distributed lock, replenishment mail ｜ Mehrlagerbestand, verteiltes Lock, Nachbestellmail |
| `route-service` | 8083 | `/api/routes/**` | VRP 路径优化、运费规则、DHL/DPD 追踪 ｜ VRP optimisation, freight rules, DHL/DPD tracking ｜ VRP-Optimierung, Frachtregeln, DHL/DPD-Tracking |
| `auth-service` | 8084 | `/api/auth/**` | JWT 签发（HS384，RBAC 声明）｜ JWT issuing (HS384, RBAC claims) ｜ JWT-Ausstellung (HS384, RBAC-Claims) |
| `report-service` | 8085 | `/api/reports/**` | 看板真实聚合 + ECharts 页面 ｜ real dashboard aggregation + ECharts page ｜ echte Dashboard-Aggregation + ECharts-Seite |

**其它界面 / Other UIs / Weitere Oberflächen**：Grafana `:3000`（匿名只读）· Prometheus `:9090` · Zipkin `:9411` · MailHog `:8025` · MinIO 控制台 `:9001` · WireMock 管理 `:8099`

---

## 8. 验证结果 · Verification · Verifikation

| 项目 Item / Position | 结果 Result / Ergebnis |
|---|---|
| 单元测试 Unit tests / Unit-Tests | **256 全通过**（gateway 9 · order 89 · inventory 45 · route 94 · auth 5 · report 14） |
| 端到端断言 E2E assertions / End-to-End-Assertions | **124/124**（含限流 429、熔断降级、契约失败分类、邮件解码、PDF 校验、追踪四类失败） |
| 容器 Containers / Container | **18/18 healthy**（6 服务 + 12 基础设施） |
| 重启不丢 Restart survival / Neustart-Überleben | 状态机（DB）与邮件节流窗口（Redis）**双双渡过容器重启** |
| 缺陷复盘 Documented defects / Dokumentierte Fehler | **56 项**（含根因与修复，见 [`todo.md`](todo.md)） |
| 外部真实契约 Real external contracts / Echte externe Verträge | 3 个：Amazon SP-API · MinIO · DHL/DPD（契约桩以**文件**版本化） |

---

## 9. 已知限制 · Known limitations · Bekannte Einschränkungen

| 中文 | English | Deutsch |
|---|---|---|
| **数据库未拆**：所有服务共用一个 PostgreSQL 实例（各自独立 Flyway 历史表），更接近"分布式单体"；拆库需要先有服务注册/配置中心（见 P2 路线图）。 | **Database not split**: all services share one PostgreSQL instance (separate Flyway histories), which is closer to a "distributed monolith"; splitting requires service discovery / config management first (see P2 roadmap). | **Datenbank nicht getrennt**: alle Dienste teilen eine PostgreSQL-Instanz (getrennte Flyway-Historien) – eher ein „verteilter Monolith"; eine Trennung setzt Service Discovery/Config Management voraus (siehe P2-Roadmap). |
| **生产化缺口**：观测端点未鉴权、Grafana 匿名只读、JWT 为对称密钥（RS256 + JWKS 在 P2）、无 CI/CD 与质量门禁、追踪未落地为事件表（因此做不了"包裹卡住 3 天"的主动告警）。 | **Production gaps**: actuator endpoints unauthenticated, Grafana anonymous read-only, symmetric JWT secret (RS256 + JWKS is P2), no CI/CD quality gates, tracking not persisted as an event table (so no proactive "parcel stuck for 3 days" alerts). | **Produktionslücken**: Actuator-Endpunkte ohne Authentifizierung, Grafana anonym lesbar, symmetrisches JWT-Secret (RS256 + JWKS in P2), keine CI/CD-Qualitätsgates, Tracking nicht als Ereignistabelle persistiert (daher keine proaktiven „Sendung hängt seit 3 Tagen"-Alarme). |
| **外部契约未用生产凭据实战验证**：SP-API 与 DHL/DPD 均按公开文档建模并用契约桩锁定，真接入需凭据联调。 | **External contracts not verified with production credentials**: SP-API and DHL/DPD are modelled from public documentation and locked by contract stubs; real onboarding needs credential-based integration testing. | **Externe Verträge nicht mit Produktivzugangsdaten verifiziert**: SP-API und DHL/DPD sind nach öffentlicher Dokumentation modelliert und durch Contract-Stubs abgesichert; der Produktivbetrieb erfordert eine Integrationsabnahme mit echten Zugangsdaten. |

> 完整限制清单（含逐条处置建议）见 [`todo.md`](todo.md) 顶部附录的 §10。

---

## 10. 文档地图 · Documentation map · Dokumentationsübersicht

| 文件 File / Datei | 内容 Content / Inhalt |
|---|---|
| `readme.md`（本文件） | **这个项目做了什么、解决了什么问题**（中 / EN / DE 三语）｜ What was built and which problems it solves |
| [`DEMO.md`](DEMO.md) | **15 分钟现场演示脚本**：8 站动线、每站命令与台词、测试数据速查、10 条追问对答、现场排障 | Live demo script: 8 stops with commands and talking points, test data, 10 Q&A, troubleshooting |
| [`todo.md`](todo.md) | **开发全过程复盘**：各轮次日志（P1-1 … P1-10）、56 项缺陷表、接口速查、配置对照、代码地图、故障速查 | Full development journal: round-by-round logs, 56 documented defects, API cheatsheet, config matrix, code map |
