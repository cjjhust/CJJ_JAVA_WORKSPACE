#!/usr/bin/env bash
# =============================================================================
# ASLP 本地冒烟测试（Smoke Test）
#
# 作用：不依赖 Docker 构建，直接用 Maven 产出的可执行 JAR 启动全部服务，
#       按「网关 → 各服务」的顺序验证路由与健康检查，最后清理进程。
#
# 前置：
#   1) mvn clean package -T 1C          # 生成各模块 target/*.jar
#   2) docker compose up -d aslp_postgres aslp_redis   # inventory-service 依赖
#
# 用法：bash scripts/smoke-test.sh
# =============================================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${ROOT_DIR}/target/smoke-logs"
mkdir -p "${LOG_DIR}"

# 模块名:端口（网关最后启动，确保下游已就绪）
SERVICES=(
    "services/auth-service:8084"
    "services/order-service:8081"
    "services/report-service:8085"
    "services/route-service:8083"
    "services/inventory-service:8082"
)
GATEWAY="gateway:8080"

PIDS=()
PASS=0
FAIL=0

cleanup() {
    echo ""
    echo "=== 清理进程 ==="
    for pid in "${PIDS[@]:-}"; do
        [ -n "${pid}" ] && kill "${pid}" 2>/dev/null && echo "  已停止 PID ${pid}"
    done
    wait 2>/dev/null
}
trap cleanup EXIT

jar_of() {
    local module="$1"
    ls "${ROOT_DIR}/${module}"/target/*.jar 2>/dev/null | grep -v -- '-sources' | head -1
}

start_service() {
    local module="$1" port="$2" name
    name="$(basename "${module}")"
    local jar
    jar="$(jar_of "${module}")"
    if [ -z "${jar}" ]; then
        echo "  ❌ ${name}: 未找到 JAR，请先执行 mvn clean package -T 1C"
        FAIL=$((FAIL + 1))
        return 1
    fi
    SERVER_PORT="${port}" nohup java -jar "${jar}" >"${LOG_DIR}/${name}.log" 2>&1 &
    PIDS+=("$!")
    echo "  ▶ ${name} 启动中 (port ${port}, PID $!)"
}

wait_http() {
    local url="$1" tries="${2:-60}"
    for _ in $(seq 1 "${tries}"); do
        if curl -fsS --max-time 2 "${url}" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

check() {
    local label="$1" url="$2" expect="$3" body
    body="$(curl -fsS --max-time 5 "${url}" 2>/dev/null)"
    if echo "${body}" | grep -q "${expect}"; then
        echo "  ✅ ${label}"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望包含: ${expect}"
        echo "     实际返回: ${body:0:220}"
        FAIL=$((FAIL + 1))
    fi
}

check_post() {
    local label="$1" url="$2" expect="$3" body
    body="$(curl -fsS --max-time 15 -X POST "${url}" 2>/dev/null)"
    if echo "${body}" | grep -q "${expect}"; then
        echo "  ✅ ${label}"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望包含: ${expect}"
        echo "     实际返回: ${body:0:220}"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== 1/5 启动下游微服务 ==="
for entry in "${SERVICES[@]}"; do
    start_service "${entry%%:*}" "${entry##*:}"
done

echo ""
echo "=== 2/5 等待下游健康检查 ==="
for entry in "${SERVICES[@]}"; do
    name="$(basename "${entry%%:*}")"
    port="${entry##*:}"
    if wait_http "http://localhost:${port}/actuator/health"; then
        echo "  ✅ ${name} 就绪 (${port})"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${name} 超时未就绪 (${port})，日志见 ${LOG_DIR}/${name}.log"
        FAIL=$((FAIL + 1))
    fi
done

echo ""
echo "=== 3/5 启动 API 网关 ==="
start_service "${GATEWAY%%:*}" "${GATEWAY##*:}"
if wait_http "http://localhost:8080/actuator/health" 90; then
    echo "  ✅ aslp_gateway 就绪 (8080)"
    PASS=$((PASS + 1))
else
    echo "  ❌ 网关启动超时，日志见 ${LOG_DIR}/gateway.log"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "=== 4/5 端到端路由验证（经网关 8080）==="
check "网关健康检查       GET /actuator/health"            "http://localhost:8080/actuator/health"                       '"status":"UP"'
check "BFF 多条件分页查询 GET /bff/orders/search"           "http://localhost:8080/bff/orders/search?status=SHIPPED&page=1&size=10&warehouseCode=Bruchsal" 'BFF'
check "路由 → auth-service   GET /api/auth/health"          "http://localhost:8080/api/auth/health"                        'auth-service up'
check "路由 → order-service  GET /api/orders/health"        "http://localhost:8080/api/orders/health"                      'Order Service OK'
check "路由 → report-service GET /api/reports/health"       "http://localhost:8080/api/reports/health"                     'report-service up'
check "路由 → route-service  GET /api/routes/health"        "http://localhost:8080/api/routes/health"                      'route-service up'

echo ""
echo "=== 5/5 业务链路验证 ==="
echo "--- M1 订单统一接入流水线（P0-1：策略拉取 → DTO 标准化 → 幂等落库 → 异常打标）---"
check_post "触发订单拉取        POST /api/orders/pull" \
    "http://localhost:8080/api/orders/pull" '"success":true'
check "订单统计查询         GET  /api/orders/stats" \
    "http://localhost:8080/api/orders/stats" '"total":3'
check "多条件分页（PAID）   GET  /api/orders?status=PAID" \
    "http://localhost:8080/api/orders?status=PAID" '"total":2'
check "异常订单列表         GET  /api/orders?status=CREATED" \
    "http://localhost:8080/api/orders?status=CREATED" 'ADDRESS_INVALID'
check_post "客服远程修正异常单   POST /api/orders/AMZ-1003/correct" \
    "http://localhost:8080/api/orders/AMZ-1003/correct?warehouseCode=Bruchsal&status=PAID" '"corrected":true'
check "修正后异常清零       GET  /api/orders/stats" \
    "http://localhost:8080/api/orders/stats" '"withErrorTag":0'

echo "--- M2 多仓库存（P0-2：数据库初始化脚本已灌入两仓种子数据）---"
check "库存健康检查         GET  /api/inventory/health" \
    "http://localhost:8080/api/inventory/health" 'Bruchsal / Mönchengladbach'
check_post "Redisson 锁扣减库存  POST /api/inventory/deduct" \
    "http://localhost:8080/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2" '"success":true'
check_post "库存不足应被拒绝     POST /api/inventory/deduct(超量)" \
    "http://localhost:8080/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=999999" '"success":false'

echo "--- M4 订单状态机（FBA 退货换标，按订单号隔离）---"
STATE_ORDER="SMOKE-$(date +%s)"
post_quiet() { curl -fsS --max-time 5 -X POST "$1" >/dev/null 2>&1; }
post_quiet "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=PAY"
post_quiet "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=PICK"
post_quiet "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=SHIP"
check_post "FBA 退货（SHIPPED→FBA_RETURN_LABEL）" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=FBA_RETURN" '"currentState":"FBA_RETURN_LABEL"'
check_post "换标完成（→FBA_RELABELED）" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=RELABEL" '"currentState":"FBA_RELABELED"'
check_post "派送（→DELIVERED）" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=DELIVER" '"currentState":"DELIVERED"'
check_post "签收完成（→COMPLETED）" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=COMPLETE" '"currentState":"COMPLETED"'
check_post "非法转换被拒绝（COMPLETED→PAY）" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}/trigger?event=PAY" '"success":false'
check "状态查询            GET /api/orders/state/{id}" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}" '"currentState":"COMPLETED"'

echo "--- JWT 签发（M1 认证 / M4 网关 VPN 接入）---"
TOKEN_BODY="$(curl -fsS --max-time 5 -X POST "http://localhost:8080/api/auth/login?username=admin" 2>/dev/null)"
if echo "${TOKEN_BODY}" | grep -q '"token":"ey'; then
    echo "  ✅ 登录签发 JWT：$(echo "${TOKEN_BODY}" | cut -c1-90)..."
    PASS=$((PASS + 1))
else
    echo "  ❌ 登录签发 JWT 失败：${TOKEN_BODY:0:200}"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "========================================"
echo "  通过: ${PASS}   失败: ${FAIL}"
echo "========================================"
[ "${FAIL}" -eq 0 ] && exit 0 || exit 1
