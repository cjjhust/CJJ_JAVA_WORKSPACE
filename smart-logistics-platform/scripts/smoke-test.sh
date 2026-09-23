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
if grep -qF -- '429' <<< "${RATE_CODES}"; then
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
# P1-8：状态持久化后必须能回答"这个订单是怎么走到今天的"。
# 轨迹里既要有走过的合法迁移，也要有被拒绝的那次尝试（审计的价值就在这里）。
check "状态轨迹（审计）    GET .../state/{id}/history" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}/history" '"event":"FBA_RETURN"'
check "非法尝试留痕        GET .../state/{id}/history" \
    "http://localhost:8080/api/orders/state/${STATE_ORDER}/history" '"accepted":false'

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

echo "--- M1/M5 报表看板（P1-7：真实聚合 order-service + inventory-service）---"
# 这段断言的是"报表里的数字来自真实业务数据"，而不是"接口返回了 200"：
#  - total=3 与前面 M1 段落的 /api/orders/stats 断言同源（真的读了订单库）；
#  - lowStock 明细里出现 AMZ-9999@Mönchengladbach，与 P1-5 补货邮件里的 SKU 完全一致
#    —— 说明「低库存口径」在邮件与看板之间没有分叉（报表不自己算一套）。
check "看板聚合（未降级）  GET /api/reports/dashboard" \
    "http://localhost:8080/api/reports/dashboard" '"degraded":false'
check "看板订单数来自订单库 GET /api/reports/dashboard" \
    "http://localhost:8080/api/reports/dashboard" '"total":3'
check "看板仓库分布       GET /api/reports/dashboard" \
    "http://localhost:8080/api/reports/dashboard" '"byWarehouse":{"Bruchsal":'
check "看板低库存明细      GET /api/reports/dashboard" \
    "http://localhost:8080/api/reports/dashboard" 'AMZ-9999@Mönchengladbach'
check "看板库存阈值口径    GET /api/reports/orders" \
    "http://localhost:8080/api/reports/orders" '"available":true'
# 静态看板页由 report-service 同源托管（不经网关路由），直连端口验证资源真的打进了 jar
check "看板页面已打包      GET :8085/dashboard.html" \
    "http://localhost:8085/dashboard.html" 'ASLP 运营看板'

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

echo "--- M5 链路追踪（P1-3：Micrometer Tracing + Brave + Zipkin）---"
# 1) 追踪后端健康。Zipkin 的 /health 是**带缩进且冒号前有空格**的 JSON
#    （实际返回 `"status" : "UP"`），写死无空格的子串会假失败 —— 与 Grafana 同一类坑（§9 #32）。
ZIPKIN_HEALTH="$(curl -fsS --max-time 10 http://localhost:9411/health 2>/dev/null)"
if grep -qE -- '"status"[[:space:]]*:[[:space:]]*"UP"' <<< "${ZIPKIN_HEALTH}"; then
    echo "  ✅ Zipkin 健康          GET  :9411/health（status UP）"
    PASS=$((PASS + 1))
else
    echo "  ❌ Zipkin 不健康：${ZIPKIN_HEALTH:0:200}"
    FAIL=$((FAIL + 1))
fi
# 2) 6 个服务都要在上报 span。网关最容易掉队：它是 WebFlux，走 WebClient 发 span，
#    URL 里的下划线主机名会让 java.net.URI 解析不出 host（实测报 Host is not specified，见 §9 #35）。
ZIPKIN_SERVICES=""
SERVICE_COUNT=0
for _ in $(seq 1 16); do
    ZIPKIN_SERVICES="$(curl -fsS --max-time 10 http://localhost:9411/api/v2/services 2>/dev/null)"
    SERVICE_COUNT="$(grep -o '"[a-z-]*"' <<< "${ZIPKIN_SERVICES}" | wc -l | tr -d ' ')"
    [ "${SERVICE_COUNT}" = "6" ] && break
    sleep 3
done
if [ "${SERVICE_COUNT}" = "6" ]; then
    echo "  ✅ 6 个服务均已上报 span：${ZIPKIN_SERVICES:0:130}"
    PASS=$((PASS + 1))
else
    echo "  ❌ 上报 span 的服务数为 ${SERVICE_COUNT}（期望 6）：${ZIPKIN_SERVICES:0:200}"
    FAIL=$((FAIL + 1))
fi
# 3) 核心断言：一条 trace 里同时出现 gateway 与 order-service —— 这才叫「链路串通了」，
#    只看到某个服务有 span 只能证明它在自说自话。
if command -v python3 >/dev/null 2>&1; then
    CROSS_TRACES=0
    for _ in $(seq 1 10); do
        CROSS_TRACES="$(curl -fsS --max-time 10 \
            'http://localhost:9411/api/v2/traces?serviceName=order-service&limit=200' 2>/dev/null \
            | python3 -c "
import json, sys
try:
    traces = json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
n = 0
for t in traces:
    svcs = {s.get('localEndpoint', {}).get('serviceName') for s in t}
    if {'gateway', 'order-service'} <= svcs:
        n += 1
print(n)")"
        [ "${CROSS_TRACES}" -gt 0 ] 2>/dev/null && break
        sleep 3
    done
    if [ "${CROSS_TRACES}" -gt 0 ] 2>/dev/null; then
        echo "  ✅ 跨服务同一 traceId：${CROSS_TRACES} 条 trace 同时含 gateway 与 order-service"
        PASS=$((PASS + 1))
    else
        echo "  ❌ 未找到同时含 gateway 与 order-service 的 trace（上下文没传播过去）"
        FAIL=$((FAIL + 1))
    fi
    # 4) 业务 span 带业务属性（证明追踪不止有 HTTP span）。
    #    【为何按 spanName 查而不是按 serviceName + limit】被监控抓取的服务会不断产生
    #    /actuator/health、/actuator/prometheus 的 trace（Prometheus 每 15s 一次），
    #    固定 limit 的窗口会被这些噪音洗掉（实测因此偶发假失败）。直接按 span 名查最精确。
    VRP_SPAN_TAGS=""
    for _ in $(seq 1 10); do
        VRP_SPAN_TAGS="$(curl -fsS --max-time 10 \
            'http://localhost:9411/api/v2/traces?spanName=aslp.vrp.solve&limit=10' 2>/dev/null \
            | python3 -c "
import json, sys
try:
    traces = json.load(sys.stdin)
except Exception:
    print(''); raise SystemExit
for t in traces:
    for s in t:
        tags = s.get('tags', {})
        if s.get('name') == 'aslp.vrp.solve' and 'stopCount' in tags and 'totalDistanceKm' in tags:
            print(f\"stopCount={tags['stopCount']} totalDistanceKm={tags['totalDistanceKm']}\")
            raise SystemExit
print('')")"
        [ -n "${VRP_SPAN_TAGS}" ] && break
        sleep 3
    done
    if [ -n "${VRP_SPAN_TAGS}" ]; then
        echo "  ✅ 业务 span 已上报并带业务属性（aslp.vrp.solve ${VRP_SPAN_TAGS}）"
        PASS=$((PASS + 1))
    else
        echo "  ❌ Zipkin 里没有带业务属性的 aslp.vrp.solve span"
        FAIL=$((FAIL + 1))
    fi
else
    echo "  ℹ️  未安装 python3，跳过 trace 解析相关的 2 项断言"
    PASS=$((PASS + 2))
fi
# 5) traceId 进日志（MDC）：有了它才能从「一条报错日志」直接跳到「整条调用链」。
#    这一项依赖 docker logs，故非容器模式（--external 打裸进程）时跳过。
if docker inspect aslp_order_service >/dev/null 2>&1; then
    # 用 --since 而不是 --tail N：P1-2 的故障演练会刷出大量重试/堆栈日志，
    # 固定 tail 行数会把带 traceId 的业务日志挤出窗口（实测 tail 500 偶发假失败）。
    # 再用 here-string 而非管道：docker logs 输出大，grep -q 提前退出会 SIGPIPE（§9 #32 同一条坑）。
    ORDER_LOGS="$(docker logs --since 10m aslp_order_service 2>&1)"
    if grep -qE -- '\[[0-9a-f]{32}-[0-9a-f]{16}\]' <<< "${ORDER_LOGS}"; then
        echo "  ✅ 日志已带 traceId/spanId（MDC 生效，可直接跳 Zipkin 查链路）"
        PASS=$((PASS + 1))
    else
        echo "  ❌ 日志里没有 traceId（检查 micrometer-tracing 是否生效）"
        FAIL=$((FAIL + 1))
    fi
else
    echo "  ℹ️  非容器模式，跳过日志 traceId 断言"
    PASS=$((PASS + 1))
fi

echo "--- M5 日志聚合（P1-1b：Promtail → Loki → Grafana）---"
# 1) Loki 就绪（单机模式 /ready 返回 200）
check "Loki 就绪            GET  :3100/ready" \
    "http://localhost:3100/ready" 'ready'
# 2) Promtail 真的在采集：用采集指标而不是「端口能通」来断言 ——
#    entries_total 在增长 + parsing_errors_total=0 才说明日志被完整解析并推走。
PROMTAIL_METRICS="$(curl -fsS --max-time 10 http://localhost:9080/metrics 2>/dev/null)"
if grep -qE -- '^promtail_docker_target_entries_total [1-9]' <<< "${PROMTAIL_METRICS}" \
    && grep -qE -- '^promtail_docker_target_parsing_errors_total 0' <<< "${PROMTAIL_METRICS}"; then
    echo "  ✅ Promtail 采集中      GET  :9080/metrics（已读 $(grep -E -- '^promtail_docker_target_entries_total' <<< "${PROMTAIL_METRICS}" | awk '{print $2}') 条，解析错误 0）"
    PASS=$((PASS + 1))
else
    echo "  ❌ Promtail 未采集到日志或存在解析错误"
    echo "     $(grep -E -- '^promtail_docker_target_(entries|parsing_errors)_total' <<< "${PROMTAIL_METRICS}" | tr '\n' ' ')"
    FAIL=$((FAIL + 1))
fi
if command -v python3 >/dev/null 2>&1; then
    # 3) Loki 已收到本项目日志（有界轮询：Promtail 批量推送 + 索引刷新有延迟，见 §9 #33）
    LOKI_STREAMS=0
    for _ in $(seq 1 20); do
        LOKI_STREAMS="$(curl -fsS --max-time 10 'http://localhost:3100/loki/api/v1/query_range' \
            --data-urlencode 'query={project="aslp"}' --data-urlencode 'limit=10' 2>/dev/null \
            | python3 -c "
import json, sys
try:
    print(len(json.load(sys.stdin).get('data', {}).get('result', [])))
except Exception:
    print(0)")"
        [ "${LOKI_STREAMS}" -gt 0 ] 2>/dev/null && break
        sleep 3
    done
    if [ "${LOKI_STREAMS}" -gt 0 ] 2>/dev/null; then
        echo "  ✅ Loki 已收到本项目日志：{project=\"aslp\"} 命中 ${LOKI_STREAMS} 个日志流"
        PASS=$((PASS + 1))
    else
        echo "  ❌ Loki 里查不到 {project=\"aslp\"} 的日志（采集或推送链路有问题）"
        FAIL=$((FAIL + 1))
    fi
    # 4) 日志里带 traceId —— 把 P1-3（链路）与 P1-1b（日志）串起来：
    #    Grafana 的 Loki 数据源据此生成可点击链接，从一行日志跳到 zipkin 整条链路。
    TRACE_LINES="$(curl -fsS --max-time 15 'http://localhost:3100/loki/api/v1/query_range' \
        --data-urlencode 'query={service=~"aslp_.+"}' --data-urlencode 'limit=300' 2>/dev/null \
        | python3 -c "
import json, re, sys
pat = re.compile(r'\[[0-9a-f]{32}-[0-9a-f]{16}\]')
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
n = 0
for stream in d.get('data', {}).get('result', []):
    for _, line in stream['values']:
        if pat.search(line):
            n += 1
print(n)")"
    if [ "${TRACE_LINES}" -gt 0 ] 2>/dev/null; then
        echo "  ✅ 日志含 traceId：采样中 ${TRACE_LINES} 行可跳 Zipkin 查链路"
        PASS=$((PASS + 1))
    else
        echo "  ❌ Loki 里的日志没有 traceId（检查 micrometer-tracing 与日志格式）"
        FAIL=$((FAIL + 1))
    fi
else
    echo "  ℹ️  未安装 python3，跳过 Loki 内容解析相关的 2 项断言"
    PASS=$((PASS + 2))
fi
# 5) Grafana 侧 Loki 数据源已自动加载且连通（provisioning 生效）
GRAFANA_LOKI_HEALTH="$(curl -fsS -u "admin:${GRAFANA_ADMIN_PASSWORD}" --max-time 10 \
    'http://localhost:3000/api/datasources/uid/aslp-loki/health' 2>/dev/null)"
if grep -qE -- '"status"[[:space:]]*:[[:space:]]*"OK"' <<< "${GRAFANA_LOKI_HEALTH}"; then
    echo "  ✅ Grafana Loki 数据源已加载且连通（uid=aslp-loki，含 traceId 跳 Zipkin 的派生字段）"
    PASS=$((PASS + 1))
else
    echo "  ❌ Grafana Loki 数据源不可用：${GRAFANA_LOKI_HEALTH:0:200}"
    FAIL=$((FAIL + 1))
fi

echo "--- M5 外部平台契约（P1-4：WireMock 模拟 Amazon SP-API —— LWA / 分页 / 限流 / 超时）---"
# 契约测试本身跑在单测里（WireMock 进程内，见 SpApiOrderClientContractTest）；
# 这里补的是**容器环境**的证据：order-service 容器里的真实 SP-API 客户端
# （LWA 换令牌 + /orders/v0/orders + /orderItems）打向 aslp_wiremock 容器，
# 证明「打包进镜像 + 跨容器网络 + 走网关」这一段也通。
SPAPI_PROBE_URL="http://localhost:8080/api/orders/spapi/probe"
WIREMOCK_ADMIN="http://localhost:8099"

# 同一份响应体上做多条断言：省掉重复调用，也让「分页只调了 2 次」的计数保持精确
check_body() {
    local label="$1" expect="$2" body="$3"
    if grep -qF -- "${expect}" <<< "${body}"; then
        echo "  ✅ ${label}"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望包含: ${expect}"
        echo "     实际返回: ${body:0:300}"
        FAIL=$((FAIL + 1))
    fi
}

# 1) 桩容器就绪 + 映射已加载（映射是仓库里的文件，加载失败 = 契约资产没进部署）
WIREMOCK_HEALTH="$(curl -fsS --max-time 10 "${WIREMOCK_ADMIN}/__admin/health" 2>/dev/null)"
check_body "WireMock 契约桩容器就绪（:8099）" '"healthy"' "${WIREMOCK_HEALTH}"
# WireMock 管理端点的 JSON 是带空格的，去掉空白后计数/包含判断才稳定；
# 探针返回的是 Jackson 紧凑 JSON（无空格），所以下面**不能**去掉空格 ——
# 商品名里带空格，去掉就永远匹配不上（踩过的坑，见 readme §9 #39）
WIREMOCK_MAPPINGS="$(curl -fsS --max-time 10 "${WIREMOCK_ADMIN}/__admin/mappings" 2>/dev/null | tr -d ' \n')"
MAPPING_COUNT="$(grep -o '"urlPath"' <<< "${WIREMOCK_MAPPINGS}" | wc -l | tr -d ' ')"
if [ "${MAPPING_COUNT}" -ge 11 ] && grep -qF -- '/auth/o2/token' <<< "${WIREMOCK_MAPPINGS}"; then
    echo "  ✅ SP-API 桩映射已加载（${MAPPING_COUNT} 个，含 LWA 换令牌端点）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 桩映射未加载或不完整（解析到 ${MAPPING_COUNT} 个）"
    FAIL=$((FAIL + 1))
fi

# P1-9：追踪桩也是文件版本化的契约资产 —— 缺了它们，追踪的失败路径断言会全部变成 404
if grep -qF -- '/track/shipments' <<< "${WIREMOCK_MAPPINGS}" \
        && grep -qF -- '/tracking/v1/parcels/' <<< "${WIREMOCK_MAPPINGS}"; then
    echo "  ✅ 追踪桩映射已加载（DHL /track/shipments + DPD /tracking/v1/parcels）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 追踪桩映射缺失（DHL/DPD 端点未注册）"
    FAIL=$((FAIL + 1))
fi
# 2) 清空 WireMock 请求日志：之后的计数才是「这一次拉取」的真实开销
curl -fsS -X DELETE "${WIREMOCK_ADMIN}/__admin/requests" >/dev/null 2>&1
# 探针 POST 走网关 8080（同时验证网关路由与 JWT 放行）
SPAPI_BODY="$(curl -fsS --max-time 20 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} "${SPAPI_PROBE_URL}" 2>/dev/null)"
check_body "探针：官方订单拉取成功（网关 -> order-service -> WireMock）" '"ok":true' "${SPAPI_BODY}"
check_body "分页契约：NextToken 翻了 2 页且未被 max-pages 截断" '"pages":2,"truncated":false' "${SPAPI_BODY}"
check_body "跨页合并：2 页共 3 条订单" '"count":3' "${SPAPI_BODY}"
check_body "商品名来自 /orderItems（订单列表接口本身不含商品名）" 'AeroSleep 婴儿床 6 件套（Amazon.de）' "${SPAPI_BODY}"
check_body "城市→履约仓映射（Moenchengladbach → Mönchengladbach）" '"warehouse":"Mönchengladbach"' "${SPAPI_BODY}"
check_body "地址缺失订单自动打标（M1 异常打标链路）" '"errorTag":"ADDRESS_INVALID"' "${SPAPI_BODY}"
check_body "明细接口 500 不拖垮主流程：商品名回退占位符" '未知商品（明细接口未返回）' "${SPAPI_BODY}"

# 3) 平台调用计数：「分页真的翻了两页」的唯一硬证据
#    （urlPath 精确匹配，3 次 /orderItems 调用路径不同，不会混进来）
ORDERS_CALLS="$(curl -fsS --max-time 10 -X POST "${WIREMOCK_ADMIN}/__admin/requests/count" \
    -H 'Content-Type: application/json' \
    -d '{"method":"GET","urlPath":"/orders/v0/orders"}' 2>/dev/null | tr -d ' \n')"
check_body "平台调用计数：订单列表接口恰好被调 2 次（= 2 页）" '"count":2' "${ORDERS_CALLS}"

# 4) 故障场景：用不同 MarketplaceId 命中不同桩，验证「异常分类」在容器里同样成立
#    （分类错了，P1-2 的重试/熔断就会：该重试的不重试、不该重试的狂重试）
probe_failure() {
    curl -fsS --max-time 20 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
        "${SPAPI_PROBE_URL}?marketplaceId=$1" 2>/dev/null
}
RATE_BODY="$(probe_failure AMZN-RATE-LIMITED)"
check_body "429 限流 → RateLimitedException（可重试家族）" '"errorType":"RateLimitedException"' "${RATE_BODY}"
check_body "429 限流 → retryable=true 且解析到 Retry-After: 7" '"retryable":true,"retryAfterSeconds":7' "${RATE_BODY}"

SERVER_BODY="$(probe_failure AMZN-SERVER-ERROR)"
check_body "503 平台故障 → PlatformUnavailableException（可重试）" '"errorType":"PlatformUnavailableException"' "${SERVER_BODY}"

TIMEOUT_BODY="$(probe_failure AMZN-TIMEOUT)"
check_body "读超时（桩延迟 3s > 容器 read-timeout 1s）→ 可重试异常" '"errorType":"PlatformUnavailableException"' "${TIMEOUT_BODY}"
check_body "读超时错误信息指明是超时（而不是平台拒绝）" '超时' "${TIMEOUT_BODY}"

BAD_BODY="$(probe_failure AMZN-BAD-REQUEST)"
check_body "400 参数/契约错 → SpApiClientException（不可重试）" '"errorType":"SpApiClientException"' "${BAD_BODY}"
check_body "400 → retryable=false（重试只会打光平台配额）" '"retryable":false' "${BAD_BODY}"

EMPTY_BODY="$(probe_failure AMZN-EMPTY)"
check_body "空结果不是失败：区间内无新单时 ok=true 且 count=0" '"ok":true,"type":"ok","pages":1,"truncated":false,"count":0' "${EMPTY_BODY}"

echo "--- M3 尾程追踪（P1-9：真实 DHL / DPD 客户端 + WireMock 契约桩）---"
# 契约测试本身跑在单测里（进程内 WireMock，见 TrackingClientContractTest，15 例）；
# 这里补的是**容器环境**的证据：route-service 容器里的真实 DHL/DPD 客户端
# （单号识别 → HTTP 调用 → 报文解析 → 状态归一化 → 缓存）打向 aslp_wiremock 容器。
#
# 断言刻意分成三类：
#   ① 成功路径：两家承运商、自动识别、状态归一化（含异常态）
#   ② 缓存：同一个单号第二次必须标记 stale=true，且**上游只被调了一次**（用 WireMock 计数证明）
#   ③ 失败语义：404 / 503（含限流与超时）/ 502 / 400 —— 前端要据此决定"改单号"还是"稍后重试"
TRACKING_BASE="http://localhost:8080/api/routes/tracking"
TRACKING_ADMIN="${WIREMOCK_ADMIN}"

# 「HTTP 状态码 + 响应体片段」必须同时断言：只看状态码分不清是 503 限流还是 503 故障，
# 而这两种情况的处置完全不同（退避 vs 看对方状态页）。
# 注意不能用 curl -f：它会让非 2xx 的响应体消失，正好丢掉我们要断言的内容（readme §9 #46）。
check_tracking() {
    local label="$1" url="$2" expect_status="$3" expect_body="$4" code body
    code="$(curl -s -o "${LOG_DIR}/tracking-body.json" -w '%{http_code}' --max-time 15 \
        ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} "${url}" 2>/dev/null)"
    body="$(cat "${LOG_DIR}/tracking-body.json" 2>/dev/null)"
    if [ "${code}" = "${expect_status}" ] && grep -qF -- "${expect_body}" <<< "${body}"; then
        echo "  ✅ ${label}（HTTP ${code}）"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}"
        echo "     期望: HTTP ${expect_status} 且含 ${expect_body}"
        echo "     实际: HTTP ${code}，body=${body:0:220}"
        FAIL=$((FAIL + 1))
    fi
}

# 清零请求日志：WireMock 的请求journal 是累加的，不清零会把上一次运行（或本脚本前半段）的调用也算进来
curl -fsS --max-time 10 -X DELETE "${TRACKING_ADMIN}/__admin/requests" >/dev/null 2>&1

# 按**单号**精确统计上游调用次数：缓存断言必须落到具体单号上，
# 否则"其它单号（失败路径用的是同一个 urlPath）也会被算进来"，断言就变成玄学。
tracking_calls() {
    curl -fsS --max-time 10 -X POST "${TRACKING_ADMIN}/__admin/requests/count" \
        -H 'Content-Type: application/json' \
        -d "{\"method\":\"GET\",\"urlPath\":\"/track/shipments\",\"queryParameters\":{\"trackingNumber\":{\"equalTo\":\"$1\"}}}" \
        2>/dev/null | sed -n 's/.*"count"[^0-9]*\([0-9]*\).*/\1/p'
}

expect_calls() {
    local label="$1" expected="$2" actual="$3"
    if [ "${actual}" = "${expected}" ]; then
        echo "  ✅ ${label}（上游被调 ${actual} 次）"
        PASS=$((PASS + 1))
    else
        echo "  ❌ ${label}：上游被调 ${actual:-?} 次（期望 ${expected}）"
        FAIL=$((FAIL + 1))
    fi
}

DHL_NUMBER="00340434161094000000"     # 20 位数字 → 按长度自动识别为 DHL
DPD_NUMBER="01234567890123"           # 14 位数字 → 自动识别为 DPD

check_tracking "单号自动识别 DHL（20 位）" "${TRACKING_BASE}/${DHL_NUMBER}" 200 '"carrier":"DHL"'
check_tracking "DHL 妥投状态归一化" "${TRACKING_BASE}/${DHL_NUMBER}" 200 '"state":"DELIVERED"'
check_tracking "保留承运商原始状态码（不丢信息）" "${TRACKING_BASE}/${DHL_NUMBER}" 200 '"carrierStatus":"delivered"'
check_tracking "单号自动识别 DPD（14 位）" "${TRACKING_BASE}/${DPD_NUMBER}" 200 '"carrier":"DPD"'
check_tracking "DPD 在途状态归一化" "${TRACKING_BASE}/${DPD_NUMBER}" 200 '"state":"IN_TRANSIT"'
# 归一化最容易错的边界：异常文案里含 "deliver"（"Delivery attempt failed"），
# 判定顺序写反就会把"投递失败"显示成"已送达" —— 最坏的一类错误，必须有端到端覆盖
check_tracking "异常状态优先于妥投判定" "${TRACKING_BASE}/00340434161094000001" 200 '"state":"EXCEPTION"'

# ② 缓存：上面 3 次查同一单号，只有第一次应该真的打了上游
check_tracking "同一单号再次查询命中缓存（stale=true）" "${TRACKING_BASE}/${DHL_NUMBER}" 200 '"stale":true'
expect_calls "缓存硬证据（3 次查询只打上游 1 次）" 1 "$(tracking_calls "${DHL_NUMBER}")"
# refresh 是"绕过缓存"的显式通道（客服刚打完电话等强实时场景）→ 必须真的再打一次
check_tracking "refresh=true 强制穿透缓存（stale=false）" "${TRACKING_BASE}/${DHL_NUMBER}?refresh=true" 200 '"stale":false'
expect_calls "refresh 确实重新查了上游" 2 "$(tracking_calls "${DHL_NUMBER}")"

# ③ 失败语义
check_tracking "查无此单 → 404 TRACKING_NOT_FOUND" "${TRACKING_BASE}/00340434161094000002" 404 'TRACKING_NOT_FOUND'
check_tracking "DPD 查无此单 → 404" "${TRACKING_BASE}/01234567890999" 404 'TRACKING_NOT_FOUND'
check_tracking "429 限流 → 503 TRACKING_RATE_LIMITED" "${TRACKING_BASE}/00340434161094000429" 503 'TRACKING_RATE_LIMITED'
check_tracking "429 带 Retry-After（前端才能退避）" "${TRACKING_BASE}/00340434161094000429" 503 '"retryAfterSeconds":7'
check_tracking "5xx → 503 TRACKING_UNAVAILABLE" "${TRACKING_BASE}/00340434161094000503" 503 'TRACKING_UNAVAILABLE'
# 读超时：容器里 read-timeout=1s 而桩延迟 3s —— 返回 503 说明是我们主动超时，而不是等对方返回
check_tracking "读超时 → 503（显式超时生效，未等满桩的 3s）" "${TRACKING_BASE}/00340434161094000003" 503 'TRACKING_UNAVAILABLE'
check_tracking "无法识别承运商 → 400（要求显式指定，不猜）" "${TRACKING_BASE}/ABC-123" 400 'error'
check_tracking "非法 carrier 参数 → 400" "${TRACKING_BASE}/${DPD_NUMBER}?carrier=GLS" 400 '不支持的承运商'

echo "--- M5 邮件真实化（P1-5：MailHog + Thymeleaf 模板）---"
# P1-5 之前：SMTP 指向容器内的 localhost（必然失败），异常又被吞掉 ——
# 「补货邮件」这条链路实际上从来没有被验证过。现在有真实 SMTP（aslp_mailhog），
# 断言直接读它的 REST API，验证收件人「真的收到了什么」。
MAILHOG_API="http://localhost:8025"

MAILHOG_READY="$(curl -fsS --max-time 10 "${MAILHOG_API}/api/v2/messages" 2>/dev/null)"
check_body "MailHog 收件箱就绪（:8025 API 可读）" '"total"' "${MAILHOG_READY}"

INVENTORY_HEALTH="$(curl -fsS --max-time 10 http://localhost:8082/actuator/health 2>/dev/null)"
check_body "邮件健康探测已恢复：inventory /actuator/health 的 mail 组件为 UP" \
    '"mail":{"status":"UP"' "${INVENTORY_HEALTH}"

# 清空收件箱，保证下面的断言只针对本轮发出的邮件
curl -fsS -X DELETE "${MAILHOG_API}/api/v1/messages" >/dev/null 2>&1
TRIGGER_BODY="$(curl -fsS --max-time 20 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
    'http://localhost:8080/api/inventory/warnings/trigger' 2>/dev/null)"
check_body "手动触发低库存巡检：mailSent=true" '"mailSent":true' "${TRIGGER_BODY}"
check_body "巡检按配置阈值（10）判定" '"threshold":10' "${TRIGGER_BODY}"

# 邮件正文必须**解码后**再看：MailHog 返回的是原始 MIME，主题/正文都是
# quoted-printable 编码的（中文变成 =E5=BA=93…），而 JSON 又把 < > & 转义成
# \u003c 之类 —— 直接 grep 原文一定会假失败（本轮踩到，见 readme §9 #47）。
# 这里用标准库 email 解一遍，只输出「人类/断言真正关心的三样」。
MAIL_DECODED="$(curl -fsS --max-time 10 "${MAILHOG_API}/api/v2/messages" 2>/dev/null | python3 -c "
import email, email.header, json, sys
payload = json.load(sys.stdin)
items = payload.get('items') or []
if not items:
    print('NO-MAIL'); raise SystemExit
# MailHog 的 Raw 是个对象（{From,To,Data}），原始 MIME 在 Data 里
raw = (items[0].get('Raw') or {}).get('Data', '')
msg = email.message_from_string(raw)
print('SUBJECT:', str(email.header.make_header(email.header.decode_header(msg.get('Subject', '')))))
for part in msg.walk():
    if part.get_content_type() == 'text/html':
        print(part.get_payload(decode=True).decode(part.get_content_charset() or 'utf-8', 'replace'))
        break
" 2>/dev/null)"

if [ -z "${MAIL_DECODED}" ]; then
    echo "  ℹ️  未能解码邮件（python3 不可用？），跳过 4 项邮件内容断言"
    PASS=$((PASS + 4))
else
    check_body "MailHog 收到补货邮件（解码后主题含 [库存补货建议]）" '[库存补货建议]' "${MAIL_DECODED}"
    check_body "正文含低库存 SKU 与仓库（种子数据 AMZ-9999@Mönchengladbach）" \
        'AMZ-9999' "${MAIL_DECODED}"
    check_body "正文含建议补货量（目标水位 20 - 可用 5 = 15）" '>15<' "${MAIL_DECODED}"
    check_body "HTML 正文使用表格布局（邮件客户端兼容性）" '<table' "${MAIL_DECODED}"
fi
MAIL_RAW="$(curl -fsS --max-time 10 "${MAILHOG_API}/api/v2/messages" 2>/dev/null)"
check_body "邮件是 multipart/alternative（纯文本兜底 + HTML 两份）" 'multipart/alternative' "${MAIL_RAW}"

# 节流：显式指定 force=false 时，30 分钟窗口内不应重复发信（避免邮件轰炸）
THROTTLED="$(curl -fsS --max-time 20 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
    'http://localhost:8080/api/inventory/warnings/trigger?force=false' 2>/dev/null)"
check_body "邮件节流生效：force=false 时 mailSkipped=true（不会重复轰炸收件人）" \
    '"mailSkipped":true' "${THROTTLED}"
# P1-10：节流窗口必须落在**共享存储**里。放在进程内（AtomicReference）时，
# 「重启后仍在窗口内」与「多副本共用一个窗口」都不成立 —— 而这两点正是节流的意义。
if docker exec aslp_redis redis-cli ping >/dev/null 2>&1; then
    THROTTLE_TTL="$(docker exec aslp_redis redis-cli ttl inventory:warning:last-mail-at 2>/dev/null | tr -d '\r\n')"
    if [ "${THROTTLE_TTL}" -gt 0 ] 2>/dev/null && [ "${THROTTLE_TTL}" -le 1800 ] 2>/dev/null; then
        echo "  ✅ 节流窗口在 Redis 里（key=inventory:warning:last-mail-at，TTL=${THROTTLE_TTL}s ≤ 30min）"
        PASS=$((PASS + 1))
    else
        echo "  ❌ 节流窗口未落在 Redis（TTL=${THROTTLE_TTL}，期望 0 < TTL ≤ 1800；-2=键不存在）"
        FAIL=$((FAIL + 1))
    fi
else
    echo "  ℹ️  aslp_redis 容器不可访问，跳过「节流窗口在 Redis」断言"
fi

echo "--- M5 单据对象存储（P1-6：MinIO + 面单/报关单 PDF）---"
# 单据是对外凭证：既要生成得出来，也要能下载重打，还要能证明内容没被篡改（sha256）。
MINIO_LIVE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://localhost:9000/minio/health/live 2>/dev/null)"
check_body "MinIO 存活探测（:9000 /minio/health/live）" '200' "${MINIO_LIVE}"

ORDER_HEALTH="$(curl -fsS --max-time 10 http://localhost:8081/actuator/health 2>/dev/null)"
check_body "对象存储健康：order /actuator/health 的 minio 组件为 UP" \
    '"minio":{"status":"UP"' "${ORDER_HEALTH}"
check_body "健康详情带桶名（排查时第一个要确认的就是「在跟哪个桶说话」）" 'aslp-documents' "${ORDER_HEALTH}"

DOC_BASE="http://localhost:8080/api/orders/AMZ-1001/documents"
LABEL_BODY="$(curl -fsS --max-time 30 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
    "${DOC_BASE}/shipping-label" 2>/dev/null)"
check_body "生成面单：对象键按订单分组" '"objectKey":"orders/AMZ-1001/shipping-label-' "${LABEL_BODY}"
check_body "面单内容类型为 application/pdf" '"contentType":"application/pdf"' "${LABEL_BODY}"
if grep -qE -- '"sha256":"[0-9a-f]{64}"' <<< "${LABEL_BODY}"; then
    echo "  ✅ 面单带 sha256（对外凭证要能事后校验一致性）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 面单响应缺少 sha256 或格式不对：${LABEL_BODY:0:200}"
    FAIL=$((FAIL + 1))
fi

# 下载最新一版：真正落盘保存，既断言又是可人工打开的证据
LABEL_FILE="${LOG_DIR}/p1-6-label-e2e.pdf"
curl -fsS --max-time 20 -o "${LABEL_FILE}" ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
    "${DOC_BASE}/shipping-label" 2>/dev/null
LABEL_HEAD="$(head -c 5 "${LABEL_FILE}" 2>/dev/null)"
LABEL_SIZE="$(wc -c < "${LABEL_FILE}" 2>/dev/null | tr -d ' ')"
if [ "${LABEL_HEAD}" = "%PDF-" ] && [ "${LABEL_SIZE:-0}" -gt 800 ]; then
    echo "  ✅ 下载面单为合法 PDF（${LABEL_SIZE} 字节，已存 ${LABEL_FILE}）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 下载面单不是合法 PDF（前 5 字节=${LABEL_HEAD}，大小=${LABEL_SIZE:-0}）"
    FAIL=$((FAIL + 1))
fi

DECL_BODY="$(curl -fsS --max-time 30 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
    "${DOC_BASE}/customs-declaration" 2>/dev/null)"
check_body "生成报关单：与面单是不同的对象键（类型分流正确）" \
    '"objectKey":"orders/AMZ-1001/customs-declaration-' "${DECL_BODY}"

LIST_BODY="$(curl -fsS --max-time 20 ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} "${DOC_BASE}" 2>/dev/null)"
check_body "列举该订单单据：两类单据都能列出来" '"type":"customs-declaration"' "${LIST_BODY}"
# 计数不用等号：每次重打都会新增一个版本（历史必须留痕），所以只断言「至少 2 份」
DOC_COUNT="$(grep -oE '"count":[0-9]+' <<< "${LIST_BODY}" | head -1 | cut -d: -f2)"
if [ "${DOC_COUNT:-0}" -ge 2 ] && grep -qF -- '"type":"shipping-label"' <<< "${LIST_BODY}"; then
    echo "  ✅ 单据版本留痕：已列到 ${DOC_COUNT} 份（面单 + 报关单，含历史版本）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 单据列举异常（count=${DOC_COUNT:-0}）：${LIST_BODY:0:200}"
    FAIL=$((FAIL + 1))
fi

# 预签名 URL：用「对外端点」签出来的，宿主浏览器/作业终端可直接下载
PRESIGNED="$(grep -o '"presignedUrl":"[^"]*"' <<< "${LABEL_BODY}" | head -1 | cut -d'"' -f4)"
PRESIGNED_HEAD="$(curl -fsS --max-time 20 "${PRESIGNED}" 2>/dev/null | head -c 5)"
if [ "${PRESIGNED_HEAD}" = "%PDF-" ]; then
    echo "  ✅ 预签名 URL 可直接下载（签名用对外端点，Host 匹配）"
    PASS=$((PASS + 1))
else
    echo "  ❌ 预签名 URL 下载失败（前 5 字节=${PRESIGNED_HEAD:-空}，url=${PRESIGNED:0:120}）"
    FAIL=$((FAIL + 1))
fi

# 400 是预期的「业务拒绝」，所以**不能**用 curl -f（-f 会把 4xx 当成失败、丢掉响应体）
BAD_TYPE="$(curl -s --max-time 20 -X POST ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} \
    "${DOC_BASE}/packing-slip" 2>/dev/null)"
check_body "白名单外的单据类型被拒（400 + 可选类型清单）" '"error":"BAD_REQUEST"' "${BAD_TYPE}"

echo ""
echo "========================================"
echo "  通过: ${PASS}   失败: ${FAIL}"
echo "========================================"
[ "${FAIL}" -eq 0 ] && exit 0 || exit 1
