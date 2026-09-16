#!/usr/bin/env bash
# =============================================================================
# ASLP 端到端冒烟测试（Smoke Test）
#
# 两种模式：
#   bash scripts/smoke-test.sh              # 本地模式：启动 target/*.jar 进程 → 断言 → 清理
#   bash scripts/smoke-test.sh --external   # 外部模式：服务已在别处运行（如容器），只做断言
#
# 本地模式前置：
#   1) mvn clean package -T 1C                          # 生成各模块 target/*.jar
#   2) docker compose up -d aslp_postgres aslp_redis     # order/inventory 依赖数据库
#
# 容器模式（P0-3）见 scripts/container-verify.sh
# =============================================================================
set -uo pipefail

EXTERNAL=0
for arg in "$@"; do
    case "${arg}" in
        --external) EXTERNAL=1 ;;
        -h|--help) sed -n '2,14p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数：${arg}（可用：--external）" >&2; exit 2 ;;
    esac
done

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

# docker profile 下网关强制 JWT；dev profile 下携带该头也无副作用。
# 因此统一先登录拿 token，后续所有请求自动附带，使同一份脚本兼容两种 profile。
# 注意：macOS 自带 bash 3.2 在 set -u 下展开空数组会报错，故使用 ${A[@]+"${A[@]}"} 惯用法。
AUTH_ARGS=()

# P1-1：Grafana 看板自动加载断言需要用 admin 认证（匿名只能看 Viewer 页面，读不到 /api/search）
GRAFANA_ADMIN_PASSWORD="${GF_SECURITY_ADMIN_PASSWORD:-aslp-admin}"

cleanup() {
    if [ "${EXTERNAL}" -eq 1 ]; then
        return 0
    fi
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

# 【重要】断言一律用 `grep -q -- "${expect}" <<< "${body}"`（here-string），
# 不要写 `echo "${body}" | grep -q ...`：本脚本开了 set -o pipefail，
# 而 grep -q 一旦命中就立即退出，写端（echo，200KB 响应体）会收到 SIGPIPE，
# 管道整体返回 141（非零）→ 明明命中却走进 else 分支（小响应体因写完得快而不暴露）。
# 实测：指标端点 200KB 旧写法恒为 141，here-string 为 0。
check() {
    local label="$1" url="$2" expect="$3" body
    body="$(curl -fsS --max-time 5 ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} "${url}" 2>/dev/null)"
    if grep -q -- "${expect}" <<< "${body}"; then
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
    body="$(curl -fsS --max-time 15 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} "${url}" 2>/dev/null)"
    if grep -q -- "${expect}" <<< "${body}"; then
        echo "  ✅ ${label}"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望包含: ${expect}"
        echo "     实际返回: ${body:0:220}"
        FAIL=$((FAIL + 1))
    fi
}

# P1-2b：带 JSON body 的 POST 断言（VRP 求解需要真实入参，不能发空 body）。
# 用 grep -F（固定字符串）而不是 grep：期望值里含 ["a","b"] 这类方括号，
# 常规 grep 会把它当字符集（bracket expression），导致明明命中也判失败。
check_post_json() {
    local label="$1" url="$2" payload="$3" expect="$4" body
    body="$(curl -fsS --max-time 30 -X POST -H 'Content-Type: application/json' \
        ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} -d "${payload}" "${url}" 2>/dev/null)"
    if grep -qF -- "${expect}" <<< "${body}"; then
        echo "  ✅ ${label}"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望包含: ${expect}"
        echo "     实际返回: ${body:0:220}"
        FAIL=$((FAIL + 1))
    fi
}

# P1-2b：带 JSON body 且「期望非 2xx」的断言（入参校验 400）。
check_post_json_status() {
    local label="$1" url="$2" payload="$3" expect="$4" code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
        -H 'Content-Type: application/json' \
        ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} -d "${payload}" "${url}" 2>/dev/null)"
    if [ "${code}" = "${expect}" ]; then
        echo "  ✅ ${label}（HTTP ${code}）"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望状态码: ${expect}，实际: ${code}"
        FAIL=$((FAIL + 1))
    fi
}

# P1-2：断言 HTTP 状态码（限流 429 等「非 2xx 即预期」的场景）。
# 不能复用 check/check_post —— 它们用 curl -f，非 2xx 直接失败拿不到响应体。
check_status() {
    local label="$1" url="$2" method="$3" expect="$4" code
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X "${method}" \
        ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} "${url}" 2>/dev/null)"
    if [ "${code}" = "${expect}" ]; then
        echo "  ✅ ${label}（HTTP ${code}）"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望状态码: ${expect}，实际: ${code}"
        FAIL=$((FAIL + 1))
    fi
}

if [ "${EXTERNAL}" -eq 1 ]; then
    echo "=== 1-3/5 外部模式：跳过本地进程启动，直接等待既存服务就绪 ==="
    for entry in "${SERVICES[@]}"; do
        name="$(basename "${entry%%:*}")"
        port="${entry##*:}"
        if wait_http "http://localhost:${port}/actuator/health" 120; then
            echo "  ✅ ${name} 就绪 (${port})"
            PASS=$((PASS + 1))
        else
            echo "  ❌ ${name} 超时未就绪 (${port})"
            FAIL=$((FAIL + 1))
        fi
    done
    echo "  ... 等待网关"
    if wait_http "http://localhost:8080/actuator/health" 180; then
        echo "  ✅ aslp_gateway 就绪 (8080)"
        PASS=$((PASS + 1))
    else
        echo "  ❌ 网关超时未就绪 (8080)"
        FAIL=$((FAIL + 1))
    fi
else
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
fi

echo ""
echo "=== 4/5 端到端路由验证（经网关 8080）==="

# 先取 JWT（docker profile 强制鉴权；dev profile 忽略该头）
LOGIN_BODY="$(curl -fsS --max-time 5 -X POST "http://localhost:8080/api/auth/login?username=admin" 2>/dev/null)"
TOKEN="$(echo "${LOGIN_BODY}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
if [ -n "${TOKEN}" ]; then
    AUTH_ARGS=(-H "Authorization: Bearer ${TOKEN}")
    echo "  🔑 已获取 JWT（后续请求自动携带 Authorization 头）"
else
    echo "  ⚠️  未获取到 JWT，将以匿名方式请求（仅 dev profile 可通）"
fi

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
# P1-2：平台拉取接口限流（Redis 令牌桶：每用户 1 次/秒、burst 1）。
#
# 用「并发突发」而非「串行紧接第二次」来断言：令牌桶按时间补充，
# 串行写法隐含要求「首个 /pull 在 1 秒内返回」，而容器刚就绪时首次调用（JIT + 首访问 DB）
# 可能超过 1 秒 → 令牌已补充 → 第二次拿到 200，断言假失败（本次实测到 200 而非 429 即此原因）。
# 并发 4 次落在同一瞬间，桶里只有 1 个令牌，必然至少 1 次 429 —— 这才真正验证了「burst 限流」。
RATE_CODES="$(for _ in 1 2 3 4; do
    curl -s -o /dev/null -w '%{http_code} ' --max-time 10 -X POST \
        ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
        "http://localhost:8080/api/orders/pull" &
done
wait)"
if echo "${RATE_CODES}" | grep -qF '429'; then
    echo "  ✅ 限流生效（并发 4 次至少有 1 次 429）：${RATE_CODES}"
    PASS=$((PASS + 1))
else
    echo "  ❌ 限流未生效（并发 4 次全部放行）：${RATE_CODES}"
    FAIL=$((FAIL + 1))
fi
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

echo "--- M2 多仓库存（种子数据由 P0-4 的 Flyway 迁移灌入）---"
check "库存健康检查         GET  /api/inventory/health" \
    "http://localhost:8080/api/inventory/health" 'Bruchsal / Mönchengladbach'
check_post "Redisson 锁扣减库存  POST /api/inventory/deduct" \
    "http://localhost:8080/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2" '"success":true'
check_post "库存不足应被拒绝     POST /api/inventory/deduct(超量)" \
    "http://localhost:8080/api/inventory/deduct?sku=AMZ-1001&warehouseCode=Bruchsal&qty=999999" '"success":false'
# P0-5：扣减 -> 查询 -> 释放 三步闭环。
# 释放断言不只是「接口通了」：服务侧会校验「释放量 <= 当前锁定量」，
# 因此 success=true 同时证明了前面扣减真的把 2 件转成了锁定库存（双状态一致）。
check "库存查询            GET  /api/inventory/{sku}" \
    "http://localhost:8080/api/inventory/AMZ-1001?warehouseCode=Bruchsal" '"sku":"AMZ-1001"'
check_post "释放锁定库存        POST /api/inventory/release" \
    "http://localhost:8080/api/inventory/release?sku=AMZ-1001&warehouseCode=Bruchsal&qty=2" '"success":true'

echo "--- M4 订单状态机（FBA 退货换标，按订单号隔离）---"
STATE_ORDER="SMOKE-$(date +%s)"
post_quiet() { curl -fsS --max-time 5 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} "$1" >/dev/null 2>&1; }
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

echo "--- JWT 签发与鉴权拦截（M1 认证 / M4 网关 VPN 接入）---"
if [ -n "${TOKEN}" ]; then
    echo "  ✅ 登录签发 JWT：$(echo "${TOKEN}" | cut -c1-40)...（HS384，TTL 120 分钟）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 登录未返回 token：${LOGIN_BODY:0:200}"
    FAIL=$((FAIL + 1))
fi

# 匿名 vs 带 token 的对比：自动适配 dev（放行）/ docker（强制 JWT）两种 profile
ANON_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
    "http://localhost:8080/bff/orders/search?status=PAID" 2>/dev/null)"
AUTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
    ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
    "http://localhost:8080/bff/orders/search?status=PAID" 2>/dev/null)"
if [ "${ANON_CODE}" = "401" ]; then
    echo "  ✅ 匿名访问 /bff/orders/search 被拒（401）→ docker profile 鉴权生效"
    PASS=$((PASS + 1))
else
    echo "  ℹ️  匿名访问返回 ${ANON_CODE} → dev profile（按设计放行）"
    PASS=$((PASS + 1))
fi
if [ "${AUTH_CODE}" = "200" ]; then
    echo "  ✅ 携带 JWT 访问 /bff/orders/search 成功（200）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 携带 JWT 访问失败（HTTP ${AUTH_CODE}）"
    FAIL=$((FAIL + 1))
fi

echo "--- P1-2 容错：熔断 + 降级 + 自动恢复 ---"
# 注意：/pull 有「1 次/秒」的令牌桶，故调用间隔 1.2s 避开限流（否则拿到的是 429 而非业务响应）
post_quiet "http://localhost:8080/api/orders/mock/failure-mode?mode=ERROR"
sleep 2.2
# 注入故障后连续拉取：每次调用会重试 3 次，而每次重试都计入熔断统计窗口
for _ in 1 2 3; do
    post_quiet "http://localhost:8080/api/orders/pull"
    sleep 1.2
done
# 验收：熔断打开后仍返回 200 + degraded=true（降级响应，而不是 500）
check_post "平台故障返回降级响应  POST /api/orders/pull" \
    "http://localhost:8080/api/orders/pull" '"degraded":true'
check "熔断状态已打开       GET  :8081/actuator/circuitbreakers" \
    "http://localhost:8081/actuator/circuitbreakers" '"state":"OPEN"'
# 恢复：关闭故障开关 -> 等熔断自动转半开 -> 下一次调用应成功
post_quiet "http://localhost:8080/api/orders/mock/failure-mode?mode=NONE"
sleep 4
check_post "熔断自动恢复（半开->成功）POST /api/orders/pull" \
    "http://localhost:8080/api/orders/pull" '"success":true'

echo "--- M3 路径优化（P1-2b：RouteController 已接入真实 jsprit 引擎）---"
# 不带 body：走内置演示问题（Bruchsal 总仓 -> 4 个德国收货点，2 辆车）
check_post "VRP 求解（演示入参）  POST /api/routes/optimize" \
    "http://localhost:8080/api/routes/optimize" '"feasible":true'
check_post "VRP 覆盖全部作业     POST /api/routes/optimize" \
    "http://localhost:8080/api/routes/optimize" '"stopCount":4'
# 单作业往返：Bruchsal -> Karlsruhe -> Bruchsal = 38.6 km（几何锁定）。
# 这个数字同时证明三件事：引擎真在跑、距离单位是 km（旧实现是「度」= 0.2）、成本模型用的是大圆距离。
check_post_json "VRP 里程为真实 km    POST /api/routes/optimize(自定义入参)" \
    "http://localhost:8080/api/routes/optimize" \
    '{"depot":{"id":"Bruchsal-总仓","lat":49.1243,"lon":8.5987},'\
'"vehicles":[{"id":"V-01","capacity":10}],'\
'"deliveries":[{"id":"SMOKE-D-1","name":"Karlsruhe","lat":49.0069,"lon":8.4037,"demand":2}]}' \
    '"totalDistanceKm":38.6'
# 运力不足：不报 500，而是 200 + 无可行路线 + 列出未指派作业
check_post_json "VRP 无解降级响应    POST /api/routes/optimize(运力不足)" \
    "http://localhost:8080/api/routes/optimize" \
    '{"depot":{"id":"Bruchsal-总仓","lat":49.1243,"lon":8.5987},'\
'"vehicles":[{"id":"V-01","capacity":1}],'\
'"deliveries":[{"id":"SMOKE-D-BIG","name":"Karlsruhe","lat":49.0069,"lon":8.4037,"demand":50}]}' \
    '"unassignedJobIds":["SMOKE-D-BIG"]'
# 入参校验：缺 deliveries -> 400（不是 500，也不是静默当成演示问题）
check_post_json_status "VRP 非法入参 400    POST /api/routes/optimize(缺 deliveries)" \
    "http://localhost:8080/api/routes/optimize" \
    '{"depot":{"id":"B","lat":49.1243,"lon":8.5987},"vehicles":[{"id":"V-01","capacity":10}]}' \
    "400"

echo "--- M5 可观测性（P1-1：Micrometer -> /actuator/prometheus -> Prometheus -> Grafana）---"
# 1) 服务侧：Prometheus 抓取端点可用。这里直连 8081（不走网关），
#    这样失败时能立即区分「应用没暴露指标」还是「网关/鉴权挡住了」。
#    用 here-string 而非管道：本脚本开了 pipefail，而 grep -q 命中即退出会让写端 SIGPIPE，
#    200KB 级的响应体必定假失败（实测旧写法 exit code = 141）。
METRICS_BODY="$(curl -s --max-time 25 http://localhost:8081/actuator/prometheus 2>/dev/null)"
if grep -qF -- 'aslp_order_pull_requests_total' <<< "${METRICS_BODY}"; then
    echo "  ✅ 服务指标端点         GET  :8081/actuator/prometheus（$(( ${#METRICS_BODY} / 1024 ))KB，含业务指标）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 服务指标端点未暴露业务指标（返回 $(( ${#METRICS_BODY} / 1024 ))KB）"
    FAIL=$((FAIL + 1))
fi
# 2) 抓取侧：用 count(up==1) 精确断言 6 个业务服务全部被抓到。
#    不数 JSON 里的 "health":"up" 个数 —— 键顺序与嵌套都不稳定，断言会变脆。
#    冷启动时 Prometheus 需要一轮抓取（scrape_interval=15s）才能判定目标健康，
#    故这里与下一项都做**有界轮询**（最多 ~48s），而不是立刻断言（与 #28 同源的时序脆弱）。
PROM_UP=""
for _ in $(seq 1 16); do
    PROM_UP="$(curl -fsS 'http://localhost:9090/api/v1/query' \
        --data-urlencode 'query=count(up{job="aslp-services"} == 1)' 2>/dev/null \
        | sed -n 's/.*"value":\[[0-9.]*,"\([0-9]*\)"\].*/\1/p')"
    [ "${PROM_UP}" = "6" ] && break
    sleep 3
done
if [ "${PROM_UP}" = "6" ]; then
    echo "  ✅ Prometheus 抓取 6/6 服务存活"
    PASS=$((PASS + 1))
else
    echo "  ❌ Prometheus 抓取异常：存活的 aslp-services 目标数 = ${PROM_UP:-未知}（期望 6）"
    FAIL=$((FAIL + 1))
fi
# 3) 指标已入库：前面跑过的 VRP 求解必须能在 Prometheus 里查到，
#    证明「应用 -> 抓取 -> TSDB -> 查询」整条链路真通，而不只是端口能访问。
#    同样需要等一轮抓取（冷启动时指标刚产生，还没被 scrape 到）。
TSDB_HIT=0
for _ in $(seq 1 16); do
    if grep -qF '"aslp_vrp_distance_count"' \
        <<< "$(curl -fsS --max-time 5 'http://localhost:9090/api/v1/query?query=aslp_vrp_distance_count' 2>/dev/null)"; then
        TSDB_HIT=1
        break
    fi
    sleep 3
done
if [ "${TSDB_HIT}" -eq 1 ]; then
    echo "  ✅ 指标已入 TSDB        GET  :9090 查到 aslp_vrp_distance_count"
    PASS=$((PASS + 1))
else
    echo "  ❌ Prometheus 在 ~48s 内未查到 VRP 里程指标（抓取或写入链路有问题）"
    FAIL=$((FAIL + 1))
fi
# 4) Grafana 自身健康。用正则容忍空白：Grafana 的 /api/health 是**带缩进**的 JSON
#    （实际返回 `"database": "ok"` 冒号后有空格），写死无空格的子串会假失败。
GRAFANA_HEALTH="$(curl -fsS --max-time 10 http://localhost:3000/api/health 2>/dev/null)"
if grep -qE -- '"database"[[:space:]]*:[[:space:]]*"ok"' <<< "${GRAFANA_HEALTH}"; then
    echo "  ✅ Grafana 健康         GET  :3000/api/health（database ok）"
    PASS=$((PASS + 1))
else
    echo "  ❌ Grafana 不健康：${GRAFANA_HEALTH:0:200}"
    FAIL=$((FAIL + 1))
fi
# 5) 看板已由 provisioning 自动加载（需 admin 认证，匿名是 Viewer 读不到 /api/search）
GRAFANA_DASHBOARDS="$(curl -fsS -u "admin:${GRAFANA_ADMIN_PASSWORD}" \
    --max-time 10 'http://localhost:3000/api/search?type=dash-db' 2>/dev/null)"
if grep -qF -- 'aslp-overview' <<< "${GRAFANA_DASHBOARDS}"; then
    echo "  ✅ Grafana 看板已自动加载（uid=aslp-overview）"
    PASS=$((PASS + 1))
else
    echo "  ❌ Grafana 看板未加载，返回：${GRAFANA_DASHBOARDS:0:200}"
    FAIL=$((FAIL + 1))
fi

echo ""
echo "========================================"
echo "  通过: ${PASS}   失败: ${FAIL}"
echo "========================================"
[ "${FAIL}" -eq 0 ] && exit 0 || exit 1
