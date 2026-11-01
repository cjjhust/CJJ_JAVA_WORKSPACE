# 威胁建模与数据保护策略（Task 28）

> 系统：GDPR Security Audit System — 审计日志为 personal data 的完整性与最小化处理
> 状态：本地演示栈的威胁模型基线；生产部署前需由 DPO/法务复核并签字确认
> 方法：STRIDE 逐信任边界分析 + 数据最小化/保留策略

---

## 1. 信任边界（Trust Boundaries）

| # | 边界 | 跨界数据 | 威胁关注点 |
|---|------|----------|-----------|
| TB1 | 用户浏览器 / Keycloak | 凭据、Token | 钓鱼、Token 窃取 |
| TB2 | 客户端 → Spring Boot API（携带 JWT） | JWT claims、业务/PII 数据 | Token 伪造/重放、注入 |
| TB3 | Spring Boot → PostgreSQL | 审计记录（含链字段）、业务数据 | 篡改、注入、越权 |
| TB4 | Spring Boot → Logstash → Elasticsearch | JSON 审计事件 | 篡改/丢弃/重复投递 |
| TB5 | 运维/DBA → 基础设施 | DDL、数据修复 | 内部人员篡改审计、超级用户绕过 |
| TB6 | 应用 → 密钥/配置源 | HMAC 密钥 | 密钥泄露、非授权轮换 |

## 2. STRIDE 分析与既有缓解

| 威胁 | 场景 | 既有缓解 | 残余风险 / 待办 |
|------|------|----------|----------------|
| **S**poofing | 伪造 JWT 调 API | Keycloak 签名校验（issuer-uri）、RS256 | 生产需校验 aud claim（Task 37） |
| **T**ampering | 直改 PG 中审计记录 | HMAC 链 + append-only 触发器 + REVOKE（Task 29/31）；启动/运行时校验 + SAFE MODE（Task 38）；告警（Task 34） | 超级用户可绕过本地控制（TB5）；最终保证依赖 Task 32 签名 + 33 WORM |
| **R**epudiation | 用户否认操作 | 链式哈希绑定主体/时间/操作；审计记录含 clientIp/userAgent | IP 可伪造（X-Forwarded-For 仅取首跳）；生产需 TLS 终结层可信 |
| **I**nformation Disclosure | 审计记录中的 PII 泄露 | 写入前 `GdprDataMasker` 掩码邮箱/电话/IBAN（Task 14/16）；`requestBody` 截断至 10KB | 姓名等未自动脱敏（按设计，需 `@SensitiveField` 标注）；Kibana/ES 访问控制待 Task 37 |
| **D**enial of Service | 审计写入风暴拖垮业务 | 审计失败仅 WARN 不阻塞业务（fail-open，演示模式）；Logstash AsyncAppender 削峰 | fail-open 与 fail-closed 需按威胁模型决策（见 §4 决策 D1）；Outbox 积压告警已覆盖延迟检测 |
| **E**levation of Privilege | 越权访问审计数据 | URL 级 RBAC（admin/audit 分离）+ `@PreAuthorize` 方法级双重控制 | Keycloak 管理事件未入审计流（Task 35 剩余部分） |

## 3. 数据最小化（Data Minimization）

审计记录字段的最小化评估（DSGVO Art. 5(1)(c)）：

| 字段 | 必要性 | 处理 |
|------|--------|------|
| userId / username | 高（问责主体） | 保留原文（Keycloak sub/prefixed_username，本身非直接标识） |
| userRoles | 高（权限证明） | 保留 |
| action / resourceType / resourceId | 高（操作事实） | 保留 |
| requestMethod / requestUri / responseStatus | 高（操作上下文） | 保留 |
| clientIp | 中（来源追溯，也属 personal data） | 保留；保留期受 §4 约束 |
| userAgent | 低-中（辅助取证） | 保留，可按保留期清理 |
| requestBody | 视情况 | **必须脱敏后截断**（已实现）；含 PII 的 DTO 不应整体记录 |
| errorMessage | 视情况 | 必须脱敏（已实现） |
| duration / success | 低-中（异常检测） | 保留 |

禁止记录：密码、Token/密钥材料、未脱敏 PII（Task 35 红线）。

## 4. 保留与法律保全策略（需 DPO/法务批准）

| 项 | 建议值（本地基线） | 说明 |
|----|--------------------|------|
| 审计日志保留期 | 90 天（热）+ 归档 | 与 Task 21 可选 ILM 策略一致；最终期限由法务按行业要求（如 KWG/MaRisk、GoBD）确定 |
| Outbox SENT 清理 | 7 天（已实现） | 投递完成即清理载荷，减少 PII 副本 |
| Legal hold | ⬜ 未实现 | 事件调查期间冻结删除（Task 36 范围） |
| DSAR（访问/导出/删除） | ⬜ 未实现 | Task 36 范围 |
| 到期处置 | Kibana ILM delete 阶段 | 需记录处置动作本身（处置日志） |

**决策记录（待确认）：**
- **D1 fail-open vs fail-closed**：当前审计持久化失败不阻塞业务（演示选择）。金融场景建议 fail-closed（审计不可写时拒绝敏感操作），需业务方确认可用性代价。
- **D2 保留期限**：90 天仅为占位，须 DPO/法务批准。
- **D3 IP 的法律定性**：德国判例倾向将 IP 视为 personal data；其保留与使用须有合法利益评估。

## 5. 审计字段与链的安全属性

- 完整性：HMAC-SHA256 链（密钥 ≥32 字节），篡改/删除/重排可检测；密钥轮换后旧记录按 `signing_key_id` 校验（单密钥版本，多密钥验证待 Task 32）。
- 追加性：应用账号无 UPDATE/DELETE 权限 + 触发器拒绝 + 链头序号禁回滚（Task 31）。
- 重启一致性：链头与数据最大序号启动校验，不一致进 SAFE MODE（Task 38）。
- 已知边界：本地 HMAC 为对称密钥，持有者可伪造整链 —— 这正是 Task 32（非导出签名密钥/KMS）与 Task 33（外部 WORM 锚定）要消除的信任假设。
