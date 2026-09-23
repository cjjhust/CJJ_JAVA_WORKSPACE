#!/usr/bin/env bash
# =============================================================================
# ASLP 演示造数脚本（Demo Traffic Generator）
#
# 作用：把「三个界面」全部喂饱，避免截图/演示时出现空面板。
#   - Grafana  http://localhost:3000/d/aslp-overview   ← 业务指标 + 日志
#   - Zipkin   http://localhost:9411                   ← 跨服务调用链
#   - ECharts  http://localhost:8085/dashboard.html     ← 业务大屏
#
# 前置：docker compose up -d（18 个容器健康）
# 用法：bash scripts/demo-traffic.sh
#
# 幂等性：本脚本可重复执行。订单拉取以平台单号为唯一键，重复执行只会 updated；
#         库存扣减/释放成对出现，不会持续消耗库存。
# =============================================================================
set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
PASS=0
FAIL=0

say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s\n' "$*"; }
# 断言 HTTP 状态码
expect_code() { # expect_code <期望码> <实际码> <描述>
    if [[ "$1" == "$2" ]]; then ok "$3 → $2"; else bad "$3 → 期望 $1，实际 $2"; fi
}
# 断言响应体包含子串
expect_has() { # expect_has <子串> <响应体> <描述>
    if [[ "$2" == *"$1"* ]]; then ok "$3（含 $1）"; else bad "$3（缺 $1）：$2"; fi
}

# ---------------------------------------------------------------------------
say "0. 登录拿 JWT"
TOKEN=$(curl -s -X POST "${GATEWAY}/api/auth/login?username=admin" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [[ -z "${TOKEN}" ]]; then
    echo "  登录失败，网关是否已启动？(${GATEWAY})" >&2
    exit 1
fi
ok "admin 登录成功（token ${#TOKEN} 字符）"
AUTH=(-H "Authorization: Bearer ${TOKEN}")

# ---------------------------------------------------------------------------
say "1. 鉴权反例：匿名访问受保护接口（喂网关 401 指标）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${GATEWAY}/bff/orders/search?status=PAID")
expect_code 401 "$CODE" "匿名访问 /bff/orders/search"

# ---------------------------------------------------------------------------
say "2. M1 订单流水线：策略拉取 → 幂等落库 → 异常打标"
BODY=$(curl -s -X POST "${AUTH[@]}" "${GATEWAY}/api/orders/pull")
expect_has '"success":true' "$BODY" "平台拉取成功"
echo "     ${BODY:0:200}"

curl -s -X POST "${AUTH[@]}" "${GATEWAY}/api/orders/pull" >/dev/null
ok "二次拉取（幂等：只 updated 不 created）"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/orders/stats")
expect_has 'byStatus' "$BODY" "订单统计（ECharts 饼图/柱状图数据源）"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/orders?status=CREATED")
expect_has 'AMZ-1003' "$BODY" "异常单 AMZ-1003 已打标"

curl -s -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/orders/AMZ-1003/correct?warehouseCode=Bruchsal&status=PAID" >/dev/null
ok "客服远程修正 → 清除异常标签"

# 限流：平台拉取入口 1 次/秒，并发 4 次必然出现 429
CODES=""
for _ in 1 2 3 4; do
    CODES+="$(curl -s -o /dev/null -w '%{http_code} ' -X POST "${AUTH[@]}" "${GATEWAY}/api/orders/pull")"
done
expect_has '429' "$CODES" "并发拉取触发限流"
echo "     状态码序列：${CODES}"

# ---------------------------------------------------------------------------
say "3. M2 多仓库存：预占 → 拒绝超卖 → 释放 → 补货邮件"
BODY=$(curl -s -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2")
expect_has '"success":true' "$BODY" "扣减 2 件（可用 → 锁定）"

BODY=$(curl -s -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=999999")
expect_has '"success":false' "$BODY" "超量扣减被拒（防超卖）"

BODY=$(curl -s -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/inventory/release?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2")
expect_has '"success":true' "$BODY" "释放 2 件（锁定 → 可用）"

BODY=$(curl -s -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/inventory/warnings/trigger?force=true")
ok "低库存预警触发（force=true 绕过节流）"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/inventory/warnings/status")
expect_has 'secondsUntilMailAllowed' "$BODY" "预警状态（含跨实例邮件节流窗口）"

# ---------------------------------------------------------------------------
say "4. M3 路径优化 VRP：真实公里 + 运力不足语义"
BODY=$(curl -s -X POST "${AUTH[@]}" "${GATEWAY}/api/routes/optimize")
expect_has '"totalDistanceKm"' "$BODY" "内置演示问题求解"
echo "     ${BODY:0:200}"

# 几何锁定：Bruchsal → Karlsruhe 往返必须精确 38.6 km
BODY=$(curl -s -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"depot":{"id":"Bruchsal","lat":49.1243,"lon":8.5987},
       "vehicles":[{"id":"V-01","capacity":10}],
       "deliveries":[{"id":"D-1","name":"Karlsruhe","lat":49.0069,"lon":8.4037,"demand":2}]}' \
  "${GATEWAY}/api/routes/optimize")
expect_has '"totalDistanceKm":38.6' "$BODY" "几何锁定 38.6 km"

# 运力不足：200 + feasible:false，而不是 500
BODY=$(curl -s -X POST "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d '{"depot":{"id":"B","lat":49.1243,"lon":8.5987},
       "vehicles":[{"id":"V-01","capacity":1}],
       "deliveries":[{"id":"D-BIG","name":"Karlsruhe","lat":49.0069,"lon":8.4037,"demand":50}]}' \
  "${GATEWAY}/api/routes/optimize")
expect_has '"feasible":false' "$BODY" "运力不足 → 200 + feasible:false"

# ---------------------------------------------------------------------------
say "5. M3 尾程追踪 DHL / DPD：统一状态 + 四类失败语义"
BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/routes/tracking/00340434161094000000")
expect_has '"carrier":"DHL"' "$BODY" "20 位单号 → DHL"
expect_has '"state":"DELIVERED"' "$BODY" "DHL 已签收"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/routes/tracking/01234567890123")
expect_has '"carrier":"DPD"' "$BODY" "14 位单号 → DPD"
expect_has '"state":"IN_TRANSIT"' "$BODY" "DPD 运输中"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/routes/tracking/00340434161094000001")
expect_has '"state":"EXCEPTION"' "$BODY" "派送失败 → EXCEPTION（不被 delivered 吃掉）"

curl -s -o /dev/null -w '' "${AUTH[@]}" "${GATEWAY}/api/routes/tracking/00340434161094000000"
curl -s -o /dev/null -w '' "${AUTH[@]}" "${GATEWAY}/api/routes/tracking/00340434161094000000"
BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/routes/tracking/00340434161094000000")
expect_has '"stale":true' "$BODY" "缓存命中（上游只被打 1 次）"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" \
  "${GATEWAY}/api/routes/tracking/00340434161094000002")
expect_code 404 "$CODE" "查无此单"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" \
  "${GATEWAY}/api/routes/tracking/ABC-123")
expect_code 400 "$CODE" "识别不出承运商（不猜，要求显式指定）"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" \
  "${GATEWAY}/api/routes/tracking/00340434161094000429")
expect_code 503 "$CODE" "上游限流 → 503 + Retry-After"

# ---------------------------------------------------------------------------
say "6. M4 履约状态机 + 审计轨迹（DB 持久化）"
for e in PAY PICK SHIP FBA_RETURN RELABEL; do
    curl -s -o /dev/null -X POST "${AUTH[@]}" \
      "${GATEWAY}/api/orders/state/DEMO-001/trigger?event=${e}"
done
BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/orders/state/DEMO-001")
expect_has '"currentState":"FBA_RELABELED"' "$BODY" "PAY→PICK→SHIP→FBA_RETURN→RELABEL"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/orders/state/DEMO-001/history")
expect_has 'RELABEL' "$BODY" "审计轨迹（含被拒绝的非法迁移尝试）"

# 非法迁移：不是 4xx，而是 200 + success:false（状态机拒绝但请求本身合法，
# 审计表会记下这次被拒的尝试 —— 这正是 /history 能回答「客户说点过按钮为何没生效」的原因）
BODY=$(curl -s -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/orders/state/DEMO-001/trigger?event=SHIP")
expect_has '"success":false' "$BODY" "非法迁移被拒（200 + success:false）"

# 非法事件名 → 400（枚举转换失败，连状态机都不进）
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/orders/state/DEMO-001/trigger?event=NOT_AN_EVENT")
expect_code 400 "$CODE" "非法事件名"

# ---------------------------------------------------------------------------
say "7. 订单分页查询 + 单订单详情（真实数据源）"
# 注意：真正的多条件分页在 order-service（/api/orders 返回 items 数组）；
# 网关里的 /bff/orders/search 只是 BFF 聚合骨架，用来演示「BFF 回显筛选条件」。
BODY=$(curl -s "${AUTH[@]}" \
  "${GATEWAY}/api/orders?status=PAID&page=0&size=5")
expect_has '"items"' "$BODY" "分页查询（order-service）"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/bff/orders/search?status=PAID&page=0&size=5")
expect_has 'BFF' "$BODY" "BFF 聚合骨架（回显筛选条件，不含 null 键）"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/orders/AMZ-1001")
expect_has 'AMZ-1001' "$BODY" "单订单详情"

# ---------------------------------------------------------------------------
say "8. 报表服务（ECharts 大屏后端）"
BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/reports/orders")
expect_has 'byStatus' "$BODY" "报表订单聚合（走网关 → report-service）"

CODE=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8085/dashboard.html")
expect_code 200 "$CODE" "ECharts 看板页面"

# ---------------------------------------------------------------------------
say "9. 让看板有分布（否则饼图只有一根柱子，看不出设计意图）"
# 演示单共 3 张（AMZ-1001 Bruchsal / AMZ-1002 Mönchengladbach / AMZ-1003 缺收货城市）。
# 先重新拉一次：把 AMZ-1003 的 ADDRESS_INVALID 标签打回来（客服待处理队列非空）；
# 再用「客服远程修正」把另外两张单摊到不同状态 —— 顺序不能反，平台拉取会按平台侧
# 状态覆盖本地状态（这正是需要「人工修正」入口的原因）。
curl -s -o /dev/null -X POST "${AUTH[@]}" "${GATEWAY}/api/orders/pull"
curl -s -o /dev/null -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/orders/AMZ-1001/correct?status=SHIPPED"
curl -s -o /dev/null -X POST "${AUTH[@]}" \
  "${GATEWAY}/api/orders/AMZ-1002/correct?status=COMPLETED"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/orders/stats")
expect_has '"SHIPPED"' "$BODY" "状态分布（饼图有多个扇区）"
expect_has 'ADDRESS_INVALID' "$BODY" "异常标签分布（客服待处理非空）"
echo "     ${BODY}"

BODY=$(curl -s "${AUTH[@]}" "${GATEWAY}/api/reports/orders")
expect_has 'Mönchengladbach' "$BODY" "各履约仓订单量（柱状图两仓都有数据）"

# 注意（演示时可以直接讲）：本地「人工修正」只是临时视图 —— order-service 的
# OrderSyncTask（cron 0 */5 * * * *）下一次同步会按平台侧数据覆盖回去。
# 要让人工干预持久生效，需要覆盖标记/优先级字段（已列入 roadmap，见 readme §9）。
echo "     提示：约 5 分钟后平台同步会把上面两处人工修正覆盖回 PAID（平台是权威源）"

# ---------------------------------------------------------------------------
printf '\n\033[1m造数完成：%d 项通过 / %d 项失败\033[0m\n' "$PASS" "$FAIL"
echo '接下来看这三个界面：'
echo '  Grafana  http://localhost:3000/d/aslp-overview'
echo '  Zipkin   http://localhost:9411'
echo '  ECharts  http://localhost:8085/dashboard.html'
[[ "$FAIL" -eq 0 ]]
