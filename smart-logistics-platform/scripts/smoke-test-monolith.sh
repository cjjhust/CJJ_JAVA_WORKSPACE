#!/usr/bin/env bash
# =============================================================================
# ASLP 单体形态端到端冒烟（P1-5）
#
# 与 scripts/smoke-test.sh（微服务形态，124 项断言）互补：
# 单体是单进程，没有网关路由/服务间 HTTP 链路可测，断言聚焦在
# 「同一套业务代码以单进程运行时，各模块能力是否原样工作」：
#
#   前置：
#     docker compose -f docker-compose.monolith.yml up -d --build
#     （或本机起 app-monolith：mvn -pl app-monolith -am package 后 java -jar）
#   用法：
#     bash scripts/smoke-test-monolith.sh           # 默认打 localhost:8090
#     BASE=http://localhost:8090 bash scripts/smoke-test-monolith.sh
# =============================================================================
set -uo pipefail

BASE="${BASE:-http://localhost:8090}"
PASS=0
FAIL=0

say()  { echo ""; echo "--- $1 ---"; }
pass() { echo "  ✅ $1"; PASS=$((PASS + 1)); }
fail() { echo "  ❌ $1"; FAIL=$((FAIL + 1)); }

# 断言 HTTP 响应体包含指定片段
check_body() {
    local desc="$1" expect="$2" body="$3"
    if grep -qF -- "$expect" <<< "$body"; then
        pass "$desc"
    else
        fail "$desc（期望含 [$expect]，实际：${body:0:200}）"
    fi
}

http_body() { curl -fsS --max-time 15 "$1" 2>/dev/null || echo ""; }

# ─────────────────────────────────────────────────────────
say "0/4 就绪等待（单体进程 + 基础设施容器）"
for _ in $(seq 1 60); do
    if curl -fsS --max-time 3 "${BASE}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
        pass "单体应用就绪（${BASE}，/actuator/health = UP）"
        break
    fi
    sleep 2
    [ "$_" = 60 ] && { fail "单体应用 120s 内未就绪"; }
done

say "1/4 认证（auth 模块：RS256 签发 + JWKS 公钥发布）"
LOGIN_BODY="$(curl -fsS --max-time 15 -X POST -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"admin123"}' "${BASE}/api/auth/login" 2>/dev/null || echo "")"
check_body "登录签发 JWT" '"tokenType":"Bearer"' "${LOGIN_BODY}"
TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))' <<< "${LOGIN_BODY}" 2>/dev/null || true)"
if [ -n "${TOKEN}" ]; then
    AUTH=(-H "Authorization: Bearer ${TOKEN}")
else
    AUTH=()
fi
JWKS_BODY="$(http_body "${BASE}/api/auth/jwks.json")"
check_body "JWKS 公钥端点（RS256, kty=RSA）" '"kty":"RSA"' "${JWKS_BODY}"

say "2/4 订单（order 模块：Mock 拉取 + 统计口径）"
PULL_BODY="$(curl -fsS --max-time 20 -X POST ${AUTH[@]+"${AUTH[@]}"} "${BASE}/api/orders/pull" 2>/dev/null || echo "")"
check_body "Mock 平台拉取落库" '"success":true' "${PULL_BODY}"
STATS_BODY="$(http_body "${BASE}/api/orders/stats")"
check_body "订单统计（报表同源口径）" '"byStatus"' "${STATS_BODY}"

say "3/4 库存（inventory 模块：查询 + 预警口径）"
INV_BODY="$(http_body "${BASE}/api/inventory")"
check_body "库存列表可读" '"content"' "${INV_BODY}"
WARN_BODY="$(http_body "${BASE}/api/inventory/warnings/status")"
check_body "低库存预警状态（阈值口径唯一真相源）" '"secondsUntilMailAllowed"' "${WARN_BODY}"

say "4/4 报表聚合（report 模块：进程内数据源，无 HTTP 降级）"
DASH_BODY="$(http_body "${BASE}/api/reports/dashboard")"
check_body "看板聚合 degraded=false（进程内调用不降级）" '"degraded":false' "${DASH_BODY}"
check_body "订单区块可用（Local 数据源装配）" '"orders"' "${DASH_BODY}"
check_body "库存区块可用（Local 数据源装配）" '"inventory"' "${DASH_BODY}"

echo ""
echo "=== 单体冒烟结果：${PASS} 通过 / ${FAIL} 失败 ==="
[ "${FAIL}" -eq 0 ] || exit 1
