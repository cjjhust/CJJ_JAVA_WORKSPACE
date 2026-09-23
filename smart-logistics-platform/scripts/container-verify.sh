#!/usr/bin/env bash
# =============================================================================
# ASLP 容器全量验证（P0-3）
#
# 流程：
#   1) docker compose build          —— 6 个服务共用根 Dockerfile，按 MODULE 构建
#   2) docker compose up -d          —— 基础设施 + 全部 Java 服务
#   3) 等待全部容器 healthy
#   4) docker compose ps             —— 状态总览（供人工核对）
#   5) bash smoke-test.sh --external —— 打容器暴露的 8080 跑端到端断言
#   6) 重启 order-service，断言状态机状态仍在（P1-8 持久化硬证据：内存实现必失败）
#
# 用法：
#   bash scripts/container-verify.sh            # 全流程
#   bash scripts/container-verify.sh --no-build # 跳过镜像构建（镜像已存在时）
#
# 清理：docker compose down（不加 -v 保留数据卷）
# =============================================================================
set -uo pipefail

SKIP_BUILD=0
for arg in "$@"; do
    case "${arg}" in
        --no-build) SKIP_BUILD=1 ;;
        -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数：${arg}（可用：--no-build）" >&2; exit 2 ;;
    esac
done

cd "$(dirname "${BASH_SOURCE[0]}")/.."

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-600}"

# 期望处于运行状态的服务（不含被注释的 minio）
# 注：aslp_grafana 镜像内没有 curl/wget（已实测），因此它没有容器内健康检查，
# 就绪与否由冒烟脚本访问 :3000/api/health 断言。
EXPECTED_SERVICES=(
    aslp_postgres aslp_redis aslp_zookeeper aslp_kafka
    aslp_gateway aslp_order_service aslp_inventory_service
    aslp_route_service aslp_auth_service aslp_report_service
    aslp_prometheus aslp_grafana aslp_zipkin
    aslp_loki aslp_promtail
    # P1-4：Amazon SP-API 契约桩（order-service 的探针把它当真实平台来打）
    aslp_wiremock
    # P1-5：真实 SMTP + Web 收件箱（补货邮件）｜ P1-6：单据对象存储（面单/报关单 PDF）
    aslp_mailhog aslp_minio
)

echo "=== 1/5 构建镜像 ==="
if [ "${SKIP_BUILD}" -eq 1 ]; then
    echo "  （--no-build，跳过）"
else
    if docker compose build; then
        echo "  ✅ 镜像构建完成"
    else
        echo "  ❌ 镜像构建失败"
        exit 1
    fi
fi

echo ""
echo "=== 2/5 启动全部服务 ==="
docker compose up -d || { echo "  ❌ 启动失败"; exit 1; }

echo ""
echo "=== 3/5 等待容器 healthy ==="
deadline=$((SECONDS + HEALTH_TIMEOUT))
while :; do
    pending=0
    detail=""
    for svc in "${EXPECTED_SERVICES[@]}"; do
        status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${svc}" 2>/dev/null || echo missing)"
        case "${status}" in
            healthy|running) ;;
            missing) pending=$((pending + 1)); detail="${detail}\n    ✗ ${svc}: 容器不存在" ;;
            *)       pending=$((pending + 1)); detail="${detail}\n    ⏳ ${svc}: ${status}" ;;
        esac
    done
    if [ "${pending}" -eq 0 ]; then
        echo "  ✅ 全部 ${#EXPECTED_SERVICES[@]} 个容器就绪"
        break
    fi
    if [ "${SECONDS}" -ge "${deadline}" ]; then
        echo "  ❌ 等待超时（${HEALTH_TIMEOUT}s），仍有 ${pending} 个未就绪："
        echo -e "${detail}"
        echo ""
        echo "--- 最近日志（各异常容器）---"
        for svc in "${EXPECTED_SERVICES[@]}"; do
            status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${svc}" 2>/dev/null || echo missing)"
            case "${status}" in
                healthy|running) ;;
                *) echo "===== ${svc} (${status}) ====="; docker logs --tail 30 "${svc}" 2>&1 ;;
            esac
        done
        exit 1
    fi
    sleep 3
done

echo ""
echo "=== 4/5 容器状态总览 ==="
docker compose ps

echo ""
echo "=== 5/5 端到端断言（外部模式，打 8080）==="
bash scripts/smoke-test.sh --external
SMOKE_EXIT=$?

if [ "${SMOKE_EXIT}" -ne 0 ]; then
    echo ""
    echo "❌ 冒烟测试失败（EXIT=${SMOKE_EXIT}），跳过持久化验证"
    exit "${SMOKE_EXIT}"
fi

echo ""
echo "=== 6/6 外部化状态验证（内存 → DB / Redis）：重启容器后状态必须还在 ==="

# ---------------------------------------------------------------------------
# 6a) 状态机（DB）：重启 order-service 后仍为 FBA_RELABELED
#
# 这是「状态真的落库了」的唯一硬证据 —— 进程内存里的状态会随重启消失。
# 单元测试（OrderStateMachineServiceTest#stateSurvivesServiceRestart）用"换一个 service 实例"
# 模拟重启，但那是同一个 JVM、同一份 Spring 上下文；只有真的重启容器才能证明
# "换一个进程、换一次 Spring 启动、重新跑一次 Flyway"之后状态依然存在。
#
# 用具名订单 SMOKE-PERSIST-001（不是 smoke-test.sh 里那个随机订单号）：
# 先 reset 保证幂等，再推进到 FBA_RELABELED —— 于是重复运行本脚本不会相互干扰。
# ---------------------------------------------------------------------------
PERSIST_ORDER="SMOKE-PERSIST-001"
TOKEN="$(curl -fsS --max-time 5 -X POST "http://localhost:8080/api/auth/login?username=admin" 2>/dev/null \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
AUTH=()
[ -n "${TOKEN}" ] && AUTH=(-H "Authorization: Bearer ${TOKEN}")

state_of() {
    curl -fsS --max-time 5 ${AUTH[@]+"${AUTH[@]}"} \
        "http://localhost:8080/api/orders/state/$1" 2>/dev/null \
        | sed -n 's/.*"currentState":"\([^"]*\)".*/\1/p'
}
trigger() {
    curl -fsS --max-time 5 -X POST ${AUTH[@]+"${AUTH[@]}"} \
        "http://localhost:8080/api/orders/state/${PERSIST_ORDER}/trigger?event=$1" >/dev/null 2>&1
}

curl -fsS --max-time 5 -X POST ${AUTH[@]+"${AUTH[@]}"} \
    "http://localhost:8080/api/orders/state/${PERSIST_ORDER}/reset" >/dev/null 2>&1

for event in PAY PICK SHIP FBA_RETURN RELABEL; do
    trigger "${event}"
done
BEFORE="$(state_of "${PERSIST_ORDER}")"
echo "  重启前状态：${BEFORE:-（未取到）}"

echo "  ⏻ docker compose restart aslp_order_service ..."
docker compose restart aslp_order_service >/dev/null 2>&1
for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' aslp_order_service 2>/dev/null)" = "healthy" ] && break
    sleep 2
done
echo "  ✔ order-service 已就绪（重启后重新跑了一次 Flyway 与 Spring 启动）"

AFTER="$(state_of "${PERSIST_ORDER}")"
echo "  重启后状态：${AFTER:-（未取到）}"

if [ "${BEFORE}" = "FBA_RELABELED" ] && [ "${AFTER}" = "FBA_RELABELED" ]; then
    echo "  ✅ 状态已持久化（FBA_RELABELED 渡过了服务重启；内存实现会回到 CREATED）"
else
    echo "  ❌ 持久化验证失败：重启前=${BEFORE:-空} 重启后=${AFTER:-空}（期望均为 FBA_RELABELED）"
    exit 1
fi

# ---------------------------------------------------------------------------
# 6b) 邮件节流（Redis）：重启 inventory-service 后仍处于节流窗口内
#
# 这是「节流状态不在进程内」的唯一硬证据。原实现把"上次发信时间"放在
# AtomicReference 里 —— 重启后窗口归零，下一轮(≤60s)立刻又发一封；
# 多副本部署时更是每个副本各发一份（邮件量随副本数放大）。
# 现在窗口在 Redis（SET NX + TTL），因此：
#   - 重启后 force=false 仍应 mailSkipped=true
#   - Redis 里能看到这个键、且 TTL 落在 (0, 30min]
# ---------------------------------------------------------------------------
echo ""
echo "  ⏻ docker compose restart aslp_inventory_service ..."
docker compose restart aslp_inventory_service >/dev/null 2>&1
for _ in $(seq 1 60); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' aslp_inventory_service 2>/dev/null)" = "healthy" ] && break
    sleep 2
done
echo "  ✔ inventory-service 已就绪"

THROTTLED_AFTER_RESTART="$(curl -fsS --max-time 10 -X POST ${AUTH[@]+"${AUTH[@]}"} \
    "http://localhost:8080/api/inventory/warnings/trigger?force=false" 2>/dev/null)"
TTL_AFTER_RESTART="$(docker exec aslp_redis redis-cli ttl inventory:warning:last-mail-at 2>/dev/null | tr -d '\r\n')"

if grep -qF -- '"mailSkipped":true' <<< "${THROTTLED_AFTER_RESTART}" \
        && [ "${TTL_AFTER_RESTART}" -gt 0 ] 2>/dev/null; then
    echo "  ✅ 邮件节流窗口已外部化（重启后仍 mailSkipped=true，Redis TTL=${TTL_AFTER_RESTART}s）"
else
    echo "  ❌ 节流窗口未外部化：重启后响应=${THROTTLED_AFTER_RESTART:0:200}，Redis TTL=${TTL_AFTER_RESTART}"
    echo "     （内存实现重启后必然立刻发信 → mailSkipped 会是 false）"
    exit 1
fi
