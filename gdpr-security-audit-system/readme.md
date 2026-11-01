# GDPR Security Audit System

[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot 3.2](https://img.shields.io/badge/Spring%20Boot-3.2.3-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Keycloak](https://img.shields.io/badge/Identity-Keycloak-4D4D4D?logo=keycloak&logoColor=white)](https://www.keycloak.org/)
[![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Elastic Stack](https://img.shields.io/badge/Observability-Elastic%20Stack-005571?logo=elastic&logoColor=white)](https://www.elastic.co/)

**Current release / 当前版本 / Aktueller Stand:** Phase 1 is complete; a local, demonstrable subset of Phase 2 is implemented and verified. This is a portfolio/demo release, not a production-grade immutable audit or legal-compliance certification. Remaining external requirements are listed below and in [`task_breakdown.md`](task_breakdown.md#milestone-20-enterprise-tamper-resistant-audit--production-hardening).

---

## 中文

### 项目定位

Java 21 / Spring Boot 3 审计服务示例：Keycloak 管理身份和 Realm 角色；Spring Security 校验 JWT 并授权；AOP 记录业务审计事件；个人信息在审计副本入库前脱敏；PostgreSQL 保存审计主记录，Logback 异步将结构化事件送到 Logstash/Elasticsearch，Kibana 提供检索和仪表盘。

### 架构

```mermaid
flowchart LR
    C[API 客户端] -->|获取 JWT| K[Keycloak]
    C -->|Bearer JWT| S[Spring Security]
    K -->|Issuer / JWKS / Realm Roles| S
    S --> R[JwtRoleConverter + RBAC]
    R --> A[Spring Boot API]
    A --> U[User Service]
    A --> X[AuditAspect]
    X --> M[PII 脱敏副本]
    M --> P[(PostgreSQL audit_logs)]
    M --> L[异步 AUDIT JSON]
    L --> LS[Logstash Pipeline]
    LS --> ES[(Elasticsearch)]
    ES --> KB[Kibana Dashboard]
```

### 请求与审计流程

1. 用户通过 Keycloak 获取 JWT，Realm 角色位于 `realm_access.roles`。
2. Spring Security 校验 issuer/JWK；`JwtRoleConverter` 将 Keycloak 角色转换为 Spring `GrantedAuthority`。
3. URL 规则和方法安全注解决定是否允许业务请求。
4. `@Auditable` 触发 `AuditAspect`，抽取主体、角色、资源 ID、HTTP 状态和耗时。
5. 脱敏后的审计副本写入 PostgreSQL；结构化 AUDIT JSON 异步进入 Logstash。
6. Logstash 派生 `severity`、`environment` 并写入 Elasticsearch，Kibana 用于检索和聚合。

### 运行演示

需要 Docker Desktop、Java 21、Maven。`.env.example` 是本地开发模板；若宿主机 `8081` 已被本地 Spring Boot 占用，可将容器映射到 `8082`：

```bash
cp .env.example .env
APP_PORT=8082 docker compose up --build -d

docker compose ps
curl http://localhost:8082/api/public/health
```

- Dashboard：<http://localhost:5601/app/dashboards#/view/gdpr-audit-dashboard>
- API health：<http://localhost:8082/api/public/health>
- Keycloak：<http://localhost:8080>
- Elasticsearch：<http://localhost:9200>

![GDPR Audit Dashboard 运行截图](docs/images/gdpr-audit-dashboard.png)

图中展示操作分布、时间线、近期审计事件等。失败操作和登录趋势在当前时间窗没有匹配数据时会显示空态；登录趋势查询 `action:LOGIN`，当前业务 API 尚未接入 Keycloak 登录事件源。

### 测试

```bash
mvn test
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn -Dapi.version=1.44 verify
```

完整 `verify` 包含 GDPR 脱敏、PG/ES Testcontainers、aggregation/mapping/索引验证，以及 JaCoCo `GdprDataMasker` 行覆盖率至少 90% 的质量门槛。本机 Docker Desktop 使用用户级 socket，因此命令显式提供 `DOCKER_HOST` 和 API 版本。

### 当前安全边界

Phase 1 **不是生产级不可篡改审计**：PG 与 ES 双写并非原子事务；PG/ES 默认管理员仍可更改数据；当前没有签名链、外部 WORM 锚定或独立密钥管理。演示凭据、Keycloak `start-dev`、HTTP 和关闭 ES 安全功能仅适用于本地。生产级目标及验收标准见 Phase 2 路线图。此说明不是 DSGVO/法律合规意见，保留期与数据主体请求需由组织 DPO/法务确认。

### 项目状态与收尾验收 — 2026-11-01

#### 项目定位

这是已完成 Phase 1 的作品集演示系统，并包含一组本地可验证的 Phase 2 防篡改与运维能力。HMAC 链能在密钥未泄露且链头可信的前提下检测审计记录变化；它不是独立签名或 WORM，不能抵抗同时控制应用密钥、数据库和运行平台的管理员。项目不声明已获得 DSGVO 法律合规认证。

#### 已实现且有验证证据

| 范围 | 实现与证据 |
|------|------------|
| Phase 1（Task 01-27） | Keycloak/OIDC、RBAC、AOP 审计、PII 掩码、PG/ELK、Kibana 九面板和 Testcontainers；历史完整验收记录见下方项目收尾快照。 |
| Task 29 哈希链 | HMAC-SHA256、确定性二进制字段编码、链头悲观锁、启动一致性检查；2026-11-01 PG 集成测试验证正常链通过，篡改后失败。 |
| Task 31 数据库加固 | `scripts/db-hardening.sql`；在一次性 PostgreSQL 16 容器实测脚本安装成功，`audit_app` 可追加记录并推进链头，更新/删除审计行被拒，链头回退被触发器拒绝。该测试未改动 Compose 使用的数据库。 |
| Task 34/38 巡检基线 | 完整性巡检、SAFE MODE、Prometheus 告警规则和 Runbook 已实现；代码测试通过。 |
| Task 35 安全拒绝事件 | API 401/403 事件进入审计链并做脱敏；过滤器单测通过。 |
| Task 39/40 运维接口 | Outbox 死信查询/回放、ES `document_id` 幂等配置和审计链指标已实现。 |
| 收尾验证 | 2026-11-01 完整 `mvn -Dapi.version=1.44 verify`：BUILD SUCCESS；JaCoCo 检查 `GdprDataMasker` 51/56 行（91.1%）通过。`docker compose config --quiet` 通过；当前演示栈健康端点返回 `UP`。 |

#### 已实现但仍缺端到端或外部验收

| 范围 | 尚缺的证据 |
|------|------------|
| Task 30 Outbox | 单元测试通过；尚未在故障注入下验证 PG 到 Logstash/ES 的最终一致、重复投递和重放全链路。 |
| Task 34/39/40 运行接入 | SIEM 未连接；重复投递去重、Outbox 重放及 Prometheus 抓取未做 Compose 端到端验收。`/actuator/prometheus` 受 Spring Security 保护，匿名请求实测返回 401；抓取器需配置认证。 |
| Task 35 身份事件 | 已覆盖应用 API 的 401/403；Keycloak 登录/登出、失败认证及管理面变更事件源尚未接入。 |
| Task 28/31/38 | 威胁模型待 DPO/法务签署；加固脚本已在隔离 PG 验证，但尚未应用到长期运行的 Compose 数据库；重启/崩溃故障注入未完成。 |

#### 生产化范围（有意不在本地演示中虚构完成）

| 任务 | 前置条件 |
|------|----------|
| Task 32 KMS/HSM | 外部 KMS/HSM 账号、不可导出签名密钥及密钥生命周期策略。 |
| Task 33 独立 WORM | 与应用管理权限隔离的对象存储账户、锁定保留策略及法律批准的保留期。 |
| Task 36 DSGVO/DSAR | DPO/法务对删除权、保留依据、期限、Legal Hold 和 DSAR 流程作书面裁定。 |
| Task 37 生产部署 | 真实域名/TLS、CI/镜像仓库/Secret Manager、备份恢复目标及 RPO/RTO 演练环境。 |

**作品集收尾结论：**本仓库可作为本地可运行、测试通过的安全审计演示项目收尾；简历可描述已实现的技术能力，但须将 KMS/HSM、外部 WORM、Keycloak 管理事件和生产合规验证列为未完成/生产化工作。任务级状态与已知限制以 [`task_breakdown.md`](task_breakdown.md) 为准。


---

## English

### Project scope

A Java 21 / Spring Boot 3 audit-service demo. Keycloak manages identities and realm roles; Spring Security validates JWTs and authorizes requests; an AOP aspect records business events; PII is masked in the audit copy before persistence. PostgreSQL stores the primary audit record, while Logback asynchronously sends structured events through Logstash to Elasticsearch for Kibana search and dashboards.

### Request flow

1. The client obtains a JWT from Keycloak; realm roles are in `realm_access.roles`.
2. Spring Security validates issuer/JWK; `JwtRoleConverter` maps Keycloak roles to Spring `GrantedAuthority` values.
3. URL rules and method-security annotations authorize the API operation.
4. `@Auditable` invokes `AuditAspect` to capture principal, roles, resource ID, HTTP result, and duration.
5. The masked audit copy is stored in PostgreSQL; structured AUDIT JSON is queued asynchronously.
6. Logstash derives `severity` and `environment`, writes to Elasticsearch, and Kibana provides search and aggregation.

### Run the demo

Requirements: Docker Desktop, Java 21, Maven. Copy `.env.example` for local development. Use port `8082` if a host application already occupies `8081`:

```bash
cp .env.example .env
APP_PORT=8082 docker compose up --build -d

docker compose ps
curl http://localhost:8082/api/public/health
```

- Dashboard: <http://localhost:5601/app/dashboards#/view/gdpr-audit-dashboard>
- API health: <http://localhost:8082/api/public/health>

![GDPR Audit Dashboard](docs/images/gdpr-audit-dashboard.png)

The dashboard contains operation distribution, timeline, recent events, top users, failed operations, high-risk events, client-IP activity, and a login-event IP trend. Empty results are expected when no matching event exists in the selected time window. The login panel queries `action:LOGIN`; a Keycloak login-event source is not yet connected to this demo API.

Run tests:

```bash
mvn test
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn -Dapi.version=1.44 verify
```

The full verification covers PII masking, PostgreSQL/Elasticsearch Testcontainers, indexes/mappings/aggregations, and a JaCoCo line-coverage gate of at least 90% for `GdprDataMasker`.

### Security boundary

Phase 1 is **not production-grade immutable auditing**. PostgreSQL and Elasticsearch dual writes are not atomic; privileged administrators can still alter stored data; hash chaining, external WORM anchoring, and independent key management are not implemented. Demo credentials, Keycloak `start-dev`, HTTP, and disabled Elasticsearch security are local-development settings only. Phase 2 requirements are documented in the roadmap. Retention and data-subject handling require review by the organization's DPO/legal team; this repository is not legal advice.

---

## Deutsch

### Projektumfang

Ein Audit-Service-Demo mit Java 21 und Spring Boot 3. Keycloak verwaltet Identitäten und Realm-Rollen; Spring Security prüft JWTs und autorisiert Anfragen; ein AOP-Aspect protokolliert Geschäftsereignisse. Personenbezogene Daten werden in der Audit-Kopie vor der Speicherung maskiert. PostgreSQL speichert den primären Audit-Datensatz; strukturierte Ereignisse werden asynchron über Logstash an Elasticsearch für Suche und Kibana-Dashboards gesendet.

### Ablauf einer Anfrage

1. Der Client erhält ein JWT von Keycloak; Realm-Rollen stehen in `realm_access.roles`.
2. Spring Security prüft Issuer/JWK; `JwtRoleConverter` bildet Keycloak-Rollen auf Spring `GrantedAuthority` ab.
3. URL-Regeln und Method-Security autorisieren den API-Aufruf.
4. `@Auditable` aktiviert `AuditAspect`, der Identität, Rollen, Ressourcen-ID, HTTP-Ergebnis und Dauer erfasst.
5. Die maskierte Audit-Kopie wird in PostgreSQL gespeichert; strukturiertes AUDIT-JSON wird asynchron eingereiht.
6. Logstash ergänzt `severity` und `environment`, schreibt nach Elasticsearch; Kibana ermöglicht Suche und Aggregation.

### Demo starten

Voraussetzungen: Docker Desktop, Java 21 und Maven. `.env.example` ist für die lokale Entwicklung vorgesehen. Falls Port `8081` bereits belegt ist, Port `8082` verwenden:

```bash
cp .env.example .env
APP_PORT=8082 docker compose up --build -d

docker compose ps
curl http://localhost:8082/api/public/health
```

- Dashboard: <http://localhost:5601/app/dashboards#/view/gdpr-audit-dashboard>
- API-Health: <http://localhost:8082/api/public/health>

![GDPR-Audit-Dashboard](docs/images/gdpr-audit-dashboard.png)

Das Dashboard zeigt Operationsverteilung, Zeitverlauf, aktuelle Ereignisse, Top-Benutzer, fehlgeschlagene und risikoreiche Vorgänge sowie IP-Aktivität. Ohne passende Ereignisse im Zeitfenster ist ein leerer Zustand erwartbar. Der Login-Trend filtert `action:LOGIN`; eine Quelle für Keycloak-Loginereignisse ist noch nicht mit der Demo-API verbunden.

Tests ausführen:

```bash
mvn test
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn -Dapi.version=1.44 verify
```

Die vollständige Prüfung umfasst PII-Maskierung, PostgreSQL/Elasticsearch-Testcontainers, Indizes/Mappings/Aggregationen und eine JaCoCo-Zeilenabdeckung von mindestens 90 % für `GdprDataMasker`.

### Sicherheitsgrenzen

Phase 1 ist **keine produktionsreife manipulationssichere Audit-Plattform**. Die parallelen Schreibvorgänge nach PostgreSQL und Elasticsearch sind nicht atomar; privilegierte Administratoren können gespeicherte Daten ändern; Hash-Kette, externe WORM-Verankerung und unabhängige Schlüsselverwaltung fehlen. Demo-Zugangsdaten, Keycloak `start-dev`, HTTP und deaktivierte Elasticsearch-Sicherheit sind ausschließlich für die lokale Entwicklung vorgesehen. Die Phase-2-Anforderungen stehen in der Roadmap. Aufbewahrungsfristen und Betroffenenrechte müssen durch DPO/Rechtsabteilung geprüft werden; dieses Projekt ist keine Rechtsberatung.

---

## Debug Journal / Debug-Protokoll / 调试记录

| 观察 / Observation / Beobachtung | 原因 / Cause / Ursache | 解决 / Resolution / Lösung |
|---|---|---|
| `debugjava: command not found`，exit 127 | VS Code 启动辅助工具调用了 shell 中不存在的命令；Maven/源码不是根因。 / The editor helper invoked a missing shell command. / Das Start-Hilfsprogramm rief einen nicht vorhandenen Shell-Befehl auf. | 用 `mvn spring-boot:run` 或 VS Code Java Launch 配置启动，不要在项目中伪造 `debugjava` 命令。 / Start through Maven or a Java launch configuration. / Über Maven oder eine Java-Launch-Konfiguration starten. |
| Logstash 旧容器停在 `Created`，原对象后来消失 | 旧容器日志不可取回，无法证实最初触发原因；当前 pipeline 配置有效。 / The stale container's original failure log was unavailable. / Das ursprüngliche Fehlerprotokoll des veralteten Containers war nicht mehr verfügbar. | 只重建 Logstash：`docker compose up -d logstash`；检查 `main` pipeline 和 TCP 50000 listener，不删除数据卷。 / Recreate only Logstash and inspect its pipeline/listener. / Nur Logstash neu erstellen und Pipeline/Listener prüfen. |
| Testcontainers 找不到 Docker 或收到 HTTP 400 | Docker Desktop 使用用户级 socket，默认 API 协商不兼容。 / Docker Desktop uses a user socket and newer API negotiation. / Docker Desktop verwendet einen benutzerspezifischen Socket und eine neuere API-Aushandlung. | `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn -Dapi.version=1.44 verify`。/ Set the Docker host and API version. / Docker-Host und API-Version explizit setzen. |
| ES 写入出现 `503 unavailable_shards_exception` | HTTP endpoint 已启动，但测试索引主分片尚未 active。 / The endpoint was ready before the index primary shard. / Der HTTP-Endpunkt war bereit, bevor der primäre Index-Shard aktiv war. | 测试等待索引 health 至少 yellow 后再写；最终在 Testcontainers ES 容器内执行 curl，避免随机宿主端口映射干扰。/ Wait for yellow health and execute requests inside the container. / Auf Yellow-Status warten und Anfragen im Container ausführen. |
| JaCoCo 显示 0 classes analyzed | `includes` glob 未匹配目标类，造成规则空通过。 / The include pattern matched no class. / Das Include-Muster fand keine Klasse. | 使用 `**/GdprDataMasker*`；确认日志显示 1 class analyzed，并检查 `target/site/jacoco/jacoco.xml`。/ Match the class and verify the analyzed count. / Klassenmuster korrigieren und Anzahl geprüfter Klassen kontrollieren. |
| Kibana XY 图表报 axis `text` undefined | Saved Object 缺少 category/value axis title。/ Axis title fields were missing. / Achsentitel fehlten im Saved Object. | 为两个轴补 `title.text`，重新导入对象并在浏览器检查页面错误。/ Add both axis titles, reimport, and inspect browser errors. / Beide Achsentitel ergänzen, erneut importieren und Browserfehler prüfen. |
