## 为什么当前版本还不能用于生产（2026-11-01）

本项目适合作品集/本地演示；“测试通过”只表示已覆盖的功能路径工作，不代表满足生产级安全、可用性、可恢复性或法律合规。下面列出从实现中确认的具体差距，后续状态以验收证据为准。

### 主要差距

1. **审计链没有独立信任锚。** 当前使用 HMAC-SHA256 对称密钥，应用持有密钥且负责签名/验签；密钥从配置注入。数据库中的链头与事件记录处于同一信任域。能同时控制应用密钥和数据库的管理员可以重算链；能控制数据库的人也可能删除历史并把链头重置到仍可通过的短前缀。缺 KMS/HSM 非导出密钥、已签名检查点及独立账户 WORM（Task 32/33）。

2. **Outbox 已补齐单实例本地路径，但仍非跨库事务。** `AuditEventRecorder` 在同一个 PostgreSQL 事务内追加审计链并写 Outbox；Outbox 写失败会向外抛错并回滚链记录。relay 直连 Elasticsearch，收到 HTTP 2xx 才标记 `SENT`，非 2xx/网络错误重试并可进入死信；按 `auditLogId` PUT 使重复请求覆盖同一文档。PG 测试已验证同事务回滚，HTTP 替身测试验证 2xx 和 503→FAILED。仍待多实例消费锁/租约、真实 ES 重启/网络分区、积压恢复和回放端到端验收。该模式提供数据库内原子入队与对 ES 的至少一次尝试，不是 PG+ES 分布式原子提交（Task 30）。

3. **身份和管理面事件仍不完整。** API 401/403 过滤器已改为调用统一事务记录入口，因此与普通审计一样写入 PG 链和 Outbox；Keycloak 登录、失败认证、角色/Client 管理操作仍没有事件源。当前不应声称所有身份和管理面操作都已进入集中审计（Task 35）。

4. **本地权限加固尚未成为部署基线。** `db-hardening.sql` 已在一次性 PG 16 容器验证，但 Compose 数据库仍由初始化超级用户账号运行，脚本未接入迁移/部署流程。数据库超级用户可绕过本地权限和触发器；应用角色配置也必须在目标环境验证（Task 31/37）。

5. **告警没有独立运维信任域。** `SIEM_ALERT` 是单独 logger 名称，但 appender 仍发往本机 Logstash/控制台；Logstash/ES 或宿主机失陷时，告警也可能一起丢失。Prometheus 指标端点受 JWT 保护，匿名请求返回 401，尚无配置好的 scraper 身份、告警服务或真实 SIEM 验收（Task 34/40）。

6. **PII 保护只是有限模式匹配。** 邮箱/电话/IBAN 正则不能覆盖所有国家格式、姓名、地址、结构化/编码/拆分字段；没有字段级采集 allowlist、假名化、完整留存到期处置、Legal Hold 和 DSAR 流程。审计记录仍可能包含个人数据，必须由 DPO/法务确定处理依据、期限和请求流程（Task 28/36）。

7. **部署与恢复假设仍是单机开发环境。** Compose 使用 Keycloak `start-dev`、HTTP、ES 关闭安全认证及演示默认值；没有生产 TLS/Secret Manager、独立网络策略、HA、CI 供应链控制、异地备份、已批准的 RPO/RTO 和恢复演练。Testcontainers 通过不等于已验证故障恢复、并发容量或持续运行能力（Task 37）。

### 后续整改优先级

- [x] **Task 30 原子入队/确认基础：**新增 `AuditEventRecorder` 事务边界；Outbox 保存完整事件；移除 AOP 直接 Logback 双写；relay 对 ES 执行稳定 ID PUT，只有 2xx 才标记 SENT，失败按次数进入 FAILED；PG 故障注入验证 Outbox 插入失败时审计行和链头回滚。仍需多实例锁/租约和真实 ES 故障演练。
- [ ] **P0 / Task 30 后续：**增加多实例 Outbox 原子抢占/租约、重试退避/熔断、真实 Elasticsearch 容器端到端重复投递/断网/恢复/回放测试，并定义积压 SLO。
- [ ] **P0 / Task 35：**接入 Keycloak 登录/失败认证/管理变更事件，并验证幂等、主体映射和敏感字段过滤；API 401/403 已进入统一事务 Outbox。
- [ ] **P1 / Task 31/34/40：**把运行账号与迁移/管理账号真正分离；将授权脚本纳入迁移并在目标 PG 以应用账号做正/负向测试；把告警送到独立 SIEM；为 Prometheus 配置最小权限认证并演练告警。
- [ ] **P1 / Task 32/33：**接入 KMS/HSM 签名检查点和独立 WORM 锚定，演练轮换、验证、保留期内拒绝覆盖及独立账户恢复。
- [ ] **P1 / Task 28/36：**由 DPO/法务批准数据分类、处理依据、保留期、删除/限制处理、Legal Hold 和 DSAR 工作流，再按批准结果实现假名化与处置。
- [ ] **P1 / Task 37：**部署生产配置：TLS、OIDC audience 校验、秘密外置、ES 身份认证与授权、网络隔离、CI/SBOM/镜像扫描/签名、备份恢复和 RPO/RTO 演练。

### 简历表述边界

- 可以写：实现了基于 HMAC-SHA256 的审计链，并通过 PostgreSQL 集成测试验证篡改可检测；实现了 PostgreSQL 同事务审计+Outbox、带 Elasticsearch HTTP 确认和稳定 ID 幂等写入的单实例投递路径，并以故障注入验证事务回滚；实现 RBAC/PII 掩码演示。
- 不应写：生产级不可篡改、数据库管理员无法篡改、PG/ES 已有事务一致性、全部个人数据已满足 GDPR/DSGVO，或已通过生产安全认证。

本次增量更新前备份：`/tmp/TODO.MD.before-production-gap-notes-20261101`。

## 项目收尾快照（2026-11-01）

- **作品集范围结论：**本项目可按“本地可运行的安全审计演示系统”收尾；不能宣称生产级不可篡改审计或 DSGVO 法律合规认证。
- **哈希链修复：**移除 `AuditLog.timestamp` 的 Hibernate `@CreationTimestamp` 覆盖行为，改为仅在时间戳为空时由 `@PrePersist` 生成 UTC 微秒时间；链签名输入与落库数据一致。PostgreSQL 集成测试确认未篡改时 valid，篡改后 invalid。
- **DB 加固：**`scripts/db-hardening.sql` 增加 `audit_app` 对链头的 SELECT/UPDATE 权限。脚本在一次性 PostgreSQL 16 容器实测成功：追加审计记录/推进链头成功，UPDATE/DELETE 审计行被拒，链头回退被触发器拒绝；未改动 Compose 使用的数据库。
- **完整验证：**2026-11-01 Outbox 改造后 `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn -Dapi.version=1.44 verify` 返回 `BUILD SUCCESS`；PG 集成测试 7/7、ES 集成测试 1/1、JaCoCo `GdprDataMasker` 51/56 行（91.1%）通过 90% 门槛；Outbox 单测 3/3 覆盖 ES 2xx 与 503→FAILED。PG 故障注入验证 Outbox 写入失败时审计行和链头回滚。`docker compose config --quiet` 通过。
- **运行状态：**当前 Compose 容器仍运行，`/api/public/health` 返回 `UP`。本轮没有重建/重启正在运行的 App；因此运行中的镜像不是本轮源码部署验收。匿名访问 `/actuator/prometheus` 返回 401，抓取需配置认证。
- **文档：**README 与 `task_breakdown.md` 已按实测更新，区分本地实现、未完成的全栈验收及生产外部依赖。
- **仍待完成：**Task 30 的多实例抢占/租约和真实 ES 故障恢复演练；Task 34/40 的独立 SIEM、Prometheus 抓取器认证和告警接入；Task 35 的 Keycloak 登录/管理事件；Task 38/39 的崩溃重启及 Compose 端到端重放验收；Task 28 法务签署；Task 32 KMS/HSM；Task 33 独立 WORM；Task 36 DPO/DSAR 裁定；Task 37 生产部署与恢复演练。详见 README 与任务清单，不将这些状态伪报为已完成。
- 本次编辑前备份：`/tmp/TODO.MD.before-closeout-20261101`。

## 最新状态（2026-10-30 17:45 +0100）

- **Phase 1：27/27 完成并通过验收。** 完整 Compose 运行中；App 映射 `8082`（宿主 `8081` 原 Java 进程保留）；Dashboard 九面板；POST/PUT/DELETE 的 PG/ES 审计、`resourceId`、PII 脱敏及 severity/environment 已实测。
- **质量验证：**完整 Maven `verify` 成功；JaCoCo `GdprDataMasker` 51/56 行（91.1%），90% 门槛强制通过；PG/ES Testcontainers、PG 索引和 ES aggregation 测试通过。
- **文档：**README 已提供中文/英文/德文架构与流程、Dashboard 截图、启动说明和 Debug 知识记录。
- **Phase 2 进展：4/10。** 本轮完成 Task 29（HMAC 审计链 + 篡改检测测试）、Task 30（Transactional Outbox + 单测 3/3）、Task 31（`scripts/db-hardening.sql` append-only 加固）、Task 35（`AuditSecurityEventFilter` 把 401/403 写入审计链 + 单测 3/3）。Task 30/31 的端到端验收（compose 全栈联调、在真实 PG 上执行加固脚本并验证 REVOKE/触发器）仍待做。
- **新增任务（Phase 2 补充）：** Task 38-40 见下方"补充任务"小节。
- 本次 README/TODO 更新前备份：`/tmp/TODO.MD.before-phase2-closeout-20261030`。

---

## 企业级防篡改审计路线图（2026-10-30 16:33 +0100；2026-10-30 17:45 更新进度）

Phase 1（Task 01-27）已完成；Phase 2 部分完成后，仍不代表已可称为生产级不可篡改。

- [ ] Task 28：威胁建模、信任边界、审计字段最小化、保留/法律保全策略；由 DPO/法务确认 DSGVO 依据。 — ✅ 文档已产出（2026-10-30）：`docs/threat-model.md`（TB1-TB6 六条信任边界、STRIDE 逐项缓解与残余风险、字段最小化评估表、保留期/Legal hold/DSAR 策略与 D1-D3 决策记录）。待做：DPO/法务对 D1（fail-open/fail-closed）、D2（90 天保留占位值）、D3（IP 定性）签署确认。
- [x] Task 29（2026-10-30）：版本化规范事件、确定性序列化、全局流序号、HMAC-SHA256 `previousHash` 链（`AuditChainService`/`AuditChainVerifier`/`audit_chain_heads` 悲观锁链头 + `@Version`）；篡改/删除/重排可检测，PG Testcontainers 篡改测试通过。待做：按租户/多流拆分。
- [x] Task 30（2026-10-30）：Transactional Outbox（`AuditOutboxEvent` + `AuditOutboxPublisher`）：同事务入队、定时批量投递 AUDIT logger → Logstash、失败重试上限、SENT 过期清理；单测 3/3 通过。待做：compose 全栈端到端验收与 Logstash 侧按 `auditLogId` 幂等去重配置。
- [x] Task 31（2026-10-30）：`scripts/db-hardening.sql`：audit_app/audit_migration/audit_dba 角色拆分，`audit_logs` REVOKE UPDATE/DELETE + append-only 触发器，链头禁删/禁回滚触发器，DBA 维护操作留痕表。待做：在真实 PG 容器执行并以 `SET ROLE audit_app` 验收；明确超级用户仍能绕过本地控制。
- [ ] Task 32：KMS/HSM 非导出签名密钥，签署链头/批次检查点；实现密钥轮换、吊销、旧公钥验证和恢复手册。
- [ ] Task 33：把签名 Merkle root/链检查点锚定到独立账户 S3 Object Lock Compliance 或等效 WORM，验证保留期内无法覆盖/删除。
- [ ] Task 34：独立完整性 Verifier、链断裂/序号缺口/outbox 延迟/归档延迟检测、Prometheus/SIEM 告警及事件响应手册。 — ✅ 已实现（2026-10-30）：`AuditIntegrityMonitor`（每 30s 巡检：链校验失败→ERROR、死信出现→WARN、积压超阈值→WARN，经独立 `SIEM_ALERT` logger 输出）；`scripts/prometheus-audit-alerts.yml`（4 条告警规则：AuditChainInvalid/AuditChainHeadRegression/AuditOutboxDeadLetter/AuditOutboxBacklog）；`docs/runbooks/audit-integrity.md`（定位/保全/恢复/演练流程与已知边界）。待做：SIEM_ALERT 通道在真实 SIEM 侧的接入验证；归档延迟检测（依赖 Task 33 WORM 归档）。
- [x] Task 35（2026-10-30，部分）：`AuditSecurityEventFilter` 将 API 401/403 拒绝事件（主体、来源 IP、目标 URI、原因，PII 已脱敏）写入 HMAC 审计链，单测 3/3 通过。待做：Keycloak Event Listener/SPI 导出 LOGIN/LOGOUT/失败认证/角色与 Client 管理变更，事件字典与幂等去重。
- [ ] Task 36：DSGVO 数据最小化、假名化、主体访问/导出、留存到期处置、legal hold 和审批；由 DPO/法务批准不可删除数据的依据和期限。
- [ ] Task 37：生产 TLS、Keycloak production mode、ES 认证/授权、网络隔离、Secret Manager、SBOM/镜像签名/漏洞扫描、CI、安全/负载/故障测试及备份恢复 RPO/RTO 演练。

### 补充任务（2026-10-30 新增，原清单未覆盖的缺口）

- [ ] Task 38：审计链断点续传与重启一致性 — ✅ 已实现（2026-10-30）：`AuditChainStartupValidator` 启动时校验链头 `nextSequence` 与 `audit_logs` 最大序号一致，不一致进入 SAFE MODE（`AuditChainService.append` 拒绝写入并报错）；单测 5/5 通过（一致/空库/链头缺失/序号回退/安全模式生效）。待做：链式 append 与 Outbox 入队间崩溃的事务边界故障注入测试。
- [ ] Task 39：Outbox 死信与回放 — ✅ 已实现（2026-10-30）：`GET /api/audit/outbox/failed`（ROLE_AUDITOR）、`POST /api/admin/outbox/{id}/replay`（ROLE_ADMIN，FAILED→PENDING 重投）；Logstash `document_id => "%{auditLogId}"` 实现 ES 侧幂等去重。待做：compose 全栈端到端验证重复投递不产生重复文档。
- [ ] Task 40：审计链指标暴露 — ✅ 已实现（2026-10-30）：`AuditChainMetrics`（Micrometer Gauges + 周期链校验）：`audit_chain_length`、`audit_chain_head_next`、`audit_chain_verification`（1=valid/0=invalid，运行中每 60s 复验）、`audit_outbox_pending_depth`、`audit_outbox_failed_depth`；actuator + `micrometer-registry-prometheus` 依赖与 `/actuator/prometheus` 暴露配置已加。待做：compose 全栈确认端点输出；Grafana 告警规则接入（Task 34 前置）。

- 估算：Phase 2 剩余（28、32-34、36-37）约 30h + 补充任务（38-40）约 8h。只有外部 WORM 锚定、签名密钥隔离、追加权限、Verifier 和恢复演练都通过验收后，才可称为具备可验证的企业级防篡改能力。

---

## 项目收尾快照（2026-10-30 16:25 +0100）

### 完成率：100%
- 27 / 27 项已实现并达到项目清单验收；旧快照保留在下方作为历史。
- Task 04：用隔离、空数据目录的 Keycloak 24 容器导入 `keycloak/realm-export.json`；日志确认 `Realm 'audit-realm' imported`，OIDC discovery 返回有效 issuer。临时容器已删除，未改动现有数据库卷。
- Task 15：JaCoCo 实测 GdprDataMasker 行覆盖率 51/56 = **91.1%**；`mvn verify` 强制检查最低 90%。
- Task 17：容器 API 的 POST/PUT/DELETE 分别返回 201/200/204；PG 和 ES 均记录 CREATE/MODIFY/DELETE，三个操作的 `resourceId=11`，request method/status 正确，request body PII 已脱敏。
- Task 23：Kibana Saved Objects 共 9 个面板引用：操作分布、时间线、近期事件、Top 10 用户、失败事件、高危事件表、高危计数、客户端 IP 活跃度、登录事件 IP 趋势。Dashboard 页面已实际打开并渲染；当前时间窗无失败/LOGIN 事件时显示空态。
- Task 25-26：PG 集成测试验证持久化、查询、统计和索引；ES 集成测试验证 mappings、主分片、文档读写、搜索、aggregation 和掩码。
- Task 27：完整 Compose 业务栈已启动；Postgres healthy，Keycloak、Elasticsearch、Logstash、Kibana、App 正常，ES/Kibana 初始化任务均 exit 0。由于用户宿主 Java 进程占用 8081，Compose App 使用 `APP_PORT=8082` 运行；宿主 Java 服务未停止。
- 认证/审计数据实测：Realm 管理员 Token 成功访问 App，PG/ES 角色均为 `ROLE_ADMIN`；ES pipeline 按 CREATE/MODIFY/DELETE 产出 LOW/MEDIUM/HIGH，environment=`development`。

### 最终验证
- `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock mvn -Dapi.version=1.44 verify`：BUILD SUCCESS；最终完整运行 29 项测试，0 失败；JaCoCo 90% 质量门槛通过。
- `docker compose config --quiet`、`docker compose build app`、`APP_PORT=8082 docker compose up --build -d` 均通过。
- Dashboard 页面：`http://localhost:5601/app/dashboards#/view/gdpr-audit-dashboard`；App 健康端点：`http://localhost:8082/api/public/health`。
- `.env` 已加入 `.gitignore`；`.env.example` 提供本地开发模板。演示凭据只适用于本地开发，部署到其他环境前必须替换。
- TODO 编辑前备份：`/tmp/TODO.MD.before-final-closeout-20261030T1623`。

---

## 最新进度快照（更新于 2026-10-30 15:42 +0100）

### 完成率
- 27 项中 22 项完整完成，5 项部分完成，0 项完全未着手。
- 严格完成率：22 / 27 = **81.5%**；部分项按 50% 折算：**90.7%**。
- 完整完成：Task 01-03、05-14、16、18-22、24-26。
- 部分完成：Task 04、15、17、23、27。

### 本轮新增实现与验证
- Task 10、25：新增 PostgreSQL Testcontainers 集成测试，覆盖审计日志保存、按用户排序、操作/时间过滤、近期计数、资源类型查询。
- Task 26：新增 Elasticsearch Testcontainers 集成测试，验证 mapping、主分片就绪、文档写入/读取、refresh 与搜索。
- Testcontainers 升至 1.20.6；本机 Docker Desktop 测试命令需设置 `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` 和 `-Dapi.version=1.44`。
- Maven Failsafe 已接入 `verify`。完整 `mvn -Dapi.version=1.44 verify` 在 Docker Host 环境变量下通过：单元与集成测试共 27 项、0 失败。
- Task 23-24：已生成并导入 Kibana Saved Objects（Data View、3 个可视化、Dashboard）；Kibana API 确认 Dashboard 有 3 个面板引用。`kibana/audit-dashboard.ndjson` 可导入。
- Task 27：增加多阶段 `Dockerfile`、`.dockerignore`、Compose App/ES 模板/Kibana 导入服务；`docker compose config` 与 `docker compose build app` 均通过。临时以 `8082:8081` 启动容器 App 后健康检查 HTTP 200、数据库连接成功；因宿主机 `8081` 仍被用户进程占用，未运行 `docker compose up --build -d` 全栈同时启动验收。
- `AuditAspect` 现在从方法的标量路径参数填充 `resourceId`；新增 2 个窄单测，验证数字 ID 和无 ID 参数。

### 仍需完成
- 🟡 Task 04：尚未在删除 Keycloak 数据卷的干净环境验证 realm 自动恢复；不应为此直接删除当前数据卷。
- 🟡 Task 15：22 个脱敏测试通过，但尚未采集覆盖率来确认 >90%。
- 🟡 Task 17：CRUD 端点已实现，POST 的 PG/ES 与脱敏验证已通过；尚未端到端执行 PUT/DELETE 验证资源变更和 `resourceId` 落库。
- 🟡 Task 23：Dashboard 目前含操作类型、时间线、最近事件 3 个面板；Top 10 用户、失败事件、高危事件/异常登录面板尚未补齐。
- 🟡 Task 27：镜像和编排校验通过，但未启动完整 Compose 栈验收。宿主 Java 进程 PID 84268 仍占用 `8081`；启动容器 App 前需用户先停止该进程，避免端口冲突。

### 本轮运行记录
- 2026-10-30 15:36-15:40：完整 Maven `verify` 成功，PG 2 项、ES 1 项集成测试通过。
- 2026-10-30 15:40：Compose `es-init` 成功安装 ES 模板；`kibana-init` 返回 `successCount=5`、无 warnings。
- 2026-10-30 15:40：`docker compose build app` 成功，生成 `gdpr-security-audit-system-app` 镜像。
- 2026-10-30 15:42：临时容器 App 在 8082 健康检查 HTTP 200 后已停止并自动清理；宿主机 Java 服务未被中断。

---

## 开发进度快照（更新于 2026-10-30 15:00 +0100）

状态说明：✅ 实现并有验证证据；🟡 已有实现但验收未完整；⬜ 待开发/待验证。

### 已完成或已有实现
- ✅ Task 01-03：Keycloak、Realm/Client/角色及测试用户已配置；OIDC/Token/RBAC 有运行验证。
- ✅ Task 05-08：Resource Server、角色转换器、认证测试接口和 RBAC 流程已实现并验证。
- ✅ Task 09、11-12、14：审计实体、`@Auditable`/枚举、AOP 审计切面、PII 脱敏实现已存在；审计写入和脱敏单测有验证记录。
- ✅ Task 18：Maven 已引入 Logstash Logback Encoder 7.4。
- ✅ Task 21：ES Index Template/初始化脚本已配置，字段 mapping 有验证记录。
- ✅ Task 19-20：Logback 异步 JSON 到 Logstash、Logstash pipeline 写入 Elasticsearch 已端到端验证。
- ✅ Task 22：同一 POST 审计事件已同时查到 PostgreSQL 与 Elasticsearch。

### 部分完成或验收未完整
- 🟡 Task 04：`keycloak/realm-export.json` 已挂载并启用 `--import-realm`；尚未验证清空环境后的恢复。
- 🟡 Task 10：Repository 查询方法已实现；未逐一验证全部查询。
- 🟡 Task 13：SecurityUtils 已实现；JWT 用户字段与审计记录尚未逐字段核验。
- 🟡 Task 15：脱敏单测 22/22 通过；覆盖率是否超过 90% 未验证。
- 🟡 Task 16-17：本轮已验证 POST 创建用户，PG 请求体中的邮箱/电话/IBAN 均脱敏；完整 CRUD 验收未全部完成。
- 🟡 Task 27：Compose 已定义 Postgres、Keycloak、Elasticsearch、Logstash、Kibana；缺少 App 服务、Dockerfile 和 `.env`，全栈一键部署未完成。

### 待开发或待验证
- ⬜ Task 23：Kibana Dashboard 尚未配置；容器曾因 `.kibana*` 索引变绿超时而退出，需恢复后再配置。
- ⬜ Task 24：Saved Objects 导出/导入（可选）尚未实现。
- ⬜ Task 25-26：PostgreSQL/Elasticsearch Testcontainers 集成测试和依赖尚未实现。
### 本轮运行状态与阻塞
- 2026-10-30 14:41 左右：`docker compose up -d logstash` 成功，容器 `audit-logstash` 正常运行；日志显示 `main` pipeline started、TCP `0.0.0.0:50000` 正监听，并可连接 Elasticsearch。旧的异常容器对象已消失，因此无法取回其最初失败日志；当前启动问题已恢复，但原始触发原因无法从旧容器确认。
- 2026-10-30 14:52：应用重启未成功。Java 调试工具执行了不存在的 `debugjava` 命令，退出码 127；随后健康检查确认 `localhost:8081` 未监听。按工具规则未绕过调试工具另行启动，因此上述 POST 与 PG/ES 对账仍待完成。
- 2026-10-30 14:58：用户执行 `mvn spring-boot:run` 后应用启动成功，Tomcat 监听 8081；健康检查 HTTP 200。
- 2026-10-30 15:00：以 `admin-user` Token POST `/api/users`，HTTP 201，用户 ID 10，唯一标记 `198.51.100.77`。PG `audit_logs` 查询到第 7 条 CREATE 记录，PII 均已脱敏；ES 查询到同一事件 1 条，字段为 `severity=LOW`、`environment=development`。本轮 POST/PG/ES 验收通过。
- Kibana 上次日志为 `index_not_green_timeout`；目前 ES cluster health 为 green，但 Kibana 仍需重新启动并验证。

📌 项目一：企业级内容安全与审计系统 (Security & GDPR Audit)
优先级：P0（最高） —— 德国企业（尤其是咨询与金融）最看重数据安全、权限治理与合规审计。
1. 所需核心组件
后端：Java 21, Spring Boot 3.x, Spring Data JPA, Spring Security, OAuth2 / OIDC
IAM / 身份认证：Keycloak (取代自定义登录，直击德企痛点)
数据库：PostgreSQL 16 (主业务库)

日志与搜索：Elasticsearch 8.x, Logstash, Kibana (ELK Stack)

测试与容器：JUnit 5, Mockito, Testcontainers (用真实 ES/PostgreSQL 跑测试)

2. 项目里程碑与开发顺序

Milestone 1.1：Keycloak 身份认证与 OAuth2 集成
[x] 使用 Docker 启动 Keycloak，配置 Realm, Client 与角色（Admin, Auditor, Regular User）。→ 2026-10-30：OIDC/Token/RBAC 验证通过；详见上方 Task 01-03。
[x] Spring Boot 整合 Spring Security OAuth2 Resource Server，实现 JWT Token 校验与 RBAC 权限控制。→ 2026-10-30：Resource Server、角色映射和 RBAC 流程验证通过；详见上方 Task 05-08。
Milestone 1.2：审计日志切面与 GDPR 数据掩码
[x] 基于 AOP（切面编程）设计 @AuditLog 自定义注解，拦截敏感操作（查看、下载、修改）。→ 2026-10-30：`@Auditable`、`AuditAspect` 已实现，审计日志写入 PG 有验证记录。
[x] 实现 GDPR 数据脱敏组件：在日志写入前对 PII（邮箱、电话、IBAN 银行账号）进行正则/掩码处理。→ 2026-10-30：邮箱/电话/IBAN 脱敏实现，单测 22/22 通过；端到端 PII 落库复验仍待完成。
Milestone 1.3：ELK 异步日志链路与不可篡改审计
[x] 配置 Spring Boot 结合 Logback 将 JSON 格式的审计日志异步发送至 Logstash。→ 2026-10-30：Logstash 日志已收到 AUDIT JSON；pipeline 输出 `environment=development`、`severity=LOW`。
[x] Logstash 结构化解析日志并写入 Elasticsearch。→ 2026-10-30：POST 事件已在 ES 查到，`severity=LOW`、`environment=development`。
[ ] 在 Kibana 中配置仪表盘，实时展示“异常登录”、“高频敏感文件下载”等安全告警。
Milestone 1.4：工程化与测试
[ ] 编写 Testcontainers 单元/集成测试，确保 Keycloak 和 ES 接口逻辑正确
[ ] 编写 docker-compose.yml，支持一键拉起 Keycloak + ES + Logstash + Postgres + Java App。
```
里程碑	任务数	预估工时	建议周期
M1.1 Keycloak + OAuth2	8	~8.5h	1.5 天
M1.2 审计日志 + GDPR 脱敏	9	~13h	2 天
M1.3 ELK 异步日志链路	7	~11.5h	2 天
M1.4 工程化与测试	3	~7.5h	1.5 天
合计	27 个任务	~40.5h	~7 个工作日
```
```
完整的工作流程：
用户登录：用户通过 Keycloak 登录
获取令牌：Keycloak 颁发包含用户角色的 JWT 令牌
请求资源：用户携带 JWT 令牌访问 Spring Boot 应用的 API
令牌验证：Spring Security 验证 JWT 令牌的有效性
权限检查：JwtRoleConverter 将 Keycloak 角色转换为 Spring Security 角色
访问控制：根据配置的权限规则决定是否允许访问
总结：
OAuth2：授权框架，定义了安全的授权流程
Spring Security：安全框架，实现了 OAuth2 规范
Keycloak：身份认证服务，作为 OAuth2 授权服务器
你的应用：作为 OAuth2 资源服务器，使用 Spring Security 保护 API
这种架构实现了认证与授权分离，让你的应用专注于业务逻辑，而将身份认证的复杂性交给 Keycloak 处理。
```