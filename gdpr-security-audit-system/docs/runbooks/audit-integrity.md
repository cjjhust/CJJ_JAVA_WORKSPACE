# Runbook：审计完整性事件响应（Task 34）

> 适用系统：GDPR Security Audit System（HMAC 审计链 + Transactional Outbox + ELK）
> 告警来源：Prometheus 规则 `scripts/prometheus-audit-alerts.yml` + 应用内 SIEM_ALERT logger
> 原则：告警与审计数据分离（SIEM_ALERT 走独立 logger/appender），不以被监控存储自身作为唯一告警通道

---

## 1. 快速定位表

| 告警 | 含义 | 首选动作 |
|------|------|----------|
| `AuditChainInvalid`（critical） | 链校验失败：记录被篡改/删除/重排，或链头不一致 | 第 2 节 |
| `AuditChainHeadRegression`（critical） | 链头 nextSequence < 最大记录序号（链头回滚/数据截断） | 第 2 节 |
| `AuditOutboxDeadLetter`（warning） | Outbox 投递最终失败（FAILED>0） | 第 3 节 |
| `AuditOutboxBacklog`（warning） | PENDING 积压 > 500 持续 10 分钟（ELK 链路疑似断） | 第 3 节 |
| `event=audit_monitor_error` | 巡检自身异常（DB 不可达等） | 检查 PostgreSQL |

## 2. 链完整性事件（critical）

1. **保全现场**：立即对 `audit_logs` 全表做只读快照（`pg_dump audit_db`），不要修复式改动数据。
2. **定位断点**：调用 `GET /api/admin/audit-chain/verify`（ROLE_ADMIN），返回 `firstInvalidSequence` 与 `reason`：
   - `record HMAC mismatch` → 该序号记录内容被改（或签名密钥已轮换未同步）
   - `previous hash mismatch` / `sequence gap or duplicate` → 记录被删除/插入/重排
   - `stored chain head does not match records` → `audit_chain_heads` 被回滚
3. **核对密钥**：确认 `AUDIT_CHAIN_HMAC_KEY` / `AUDIT_CHAIN_KEY_ID` 近期是否变更；`signing_key_id` 不匹配会表现为整链 mismatch，属配置问题而非攻击。
4. **评估影响面**：从 `firstInvalidSequence` 起到链尾均为不可信区间；结合 PG `pg_stat_activity`、容器日志、Kibana 中同一时段事件交叉比对，判断是操作失误还是入侵。
5. **恢复**：确认原因后，用 `audit_migration` 账号按变更管理流程修复数据或重建链头（操作本身记入 `audit_maintenance_log`）；应用重启后 `AuditChainStartupValidator` 会重新校验，SAFE MODE 自动解除。
6. **上报**：按组织数据泄露/完整性事件流程通报 DPO 与安全负责人（GDPR Art. 33 评估是否构成 personal data breach）。

## 3. Outbox 投递事件（warning）

1. **Backlog**：`docker compose ps` 检查 logstash/es；`docker compose logs audit-logstash --tail 100`。链路恢复后 relay 自动消化积压，无需回放。
2. **DeadLetter**：
   - 查询：`GET /api/audit/outbox/failed`（ROLE_AUDITOR）
   - 修复根因（Logstash 不可用 / payload 异常）后回放：`POST /api/admin/outbox/{id}/replay`（ROLE_ADMIN）
   - ES 侧幂等由 `document_id=%{auditLogId}` 保证，重复回放不产生重复文档
3. **验证闭环**：回放后 `audit_outbox_failed_depth` 应回落为 0；在 Kibana `audit-logs-*` 按对应 `auditLogId` 查询确认文档存在。

## 4. 验证与演练

- 篡改演练：PG Testcontainers 集成测试 `AuditLogRepositoryIT.appendsAndDetectsTamperingInHmacAuditChain` 覆盖 UPDATE 场景。
- 巡检通道演练：每季度手动 UPDATE 一条测试记录 → 确认 `AuditChainInvalid` 告警在下一巡检周期（30s）触发并送达 SIEM 通道。
- 指标核对：`GET /actuator/prometheus` 中五个 `audit_*` 指标应与 DB 实际状态一致。

## 5. 已知边界

- 超级用户可绕过 PG 触发器/权限（Task 31 边界）；强保证依赖 Task 32（签名检查点）与 Task 33（外部 WORM 锚定）完成后才成立。
- 本地演示环境的 HMAC 密钥在 application.yml 中有默认值，生产部署必须通过 Secret Manager 注入（Task 37）。
