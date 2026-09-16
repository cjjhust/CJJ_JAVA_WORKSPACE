#!/usr/bin/env bash
# =============================================================================
# ASLP 容器全量验证（P0-3）
#
# 流程：
#   1) docker compose build          —— 6 个服务共用根 Dockerfile，按 MODULE 构建
#   2) docker compose up -d          —— 基础设施 + 全部 Java 服务
#   3) 等待全部容器 healthy
#   4) docker compose ps             —— 状态总览（供人工核对）
#   5) bash smoke-test.sh --external —— 打容器暴露的 8080 跑 30 项端到端断言
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
EXPECTED_SERVICES=(
    aslp_postgres aslp_redis aslp_zookeeper aslp_kafka
    aslp_gateway aslp_order_service aslp_inventory_service
    aslp_route_service aslp_auth_service aslp_report_service
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
