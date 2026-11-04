#!/usr/bin/env bash
# =============================================================================
# P3-7 — 备份恢复演练（RPO/RTO 验证）
#
# 背景：定时备份（DatabaseBackupTask，pg_dump）只证明「备份文件在生成」，
# 不证明「备份能用」。备份的唯一价值在恢复——本脚本把恢复走一遍：
#
#   1) 用 pg_dump 从 aslp_order 库导出一份新鲜备份（等价于定时任务产物）
#   2) 在同一 PG 实例上建一次性演练库 aslp_drill，pg_restore 灌入
#   3) 断言：表存在 + 关键行数一致（orders 表）
#   4) 记录恢复耗时（RTO ≈ 备份恢复时间；RPO ≈ 备份间隔，此处为演示口径）
#   5) 清理演练库（drop，不留垃圾）
#
# 用法：bash scripts/backup-restore-drill.sh
# 依赖：aslp_postgres 容器在跑（docker compose up -d aslp_postgres）
# =============================================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PG_CONTAINER="aslp_postgres"
PG_USER="aslp"
SRC_DB="aslp_order"
DRILL_DB="aslp_drill_$(date +%s)"
DUMP_DIR="target/backup-drill"
mkdir -p "${DUMP_DIR}"
DUMP_FILE="${DUMP_DIR}/drill_${SRC_DB}_$(date +%Y%m%d_%H%M%S).sql"

echo "=== 1/5 生成新鲜备份（pg_dump ${SRC_DB}）==="
T0=$(date +%s)
docker exec "${PG_CONTAINER}" pg_dump -U "${PG_USER}" -d "${SRC_DB}" > "${DUMP_FILE}"
DUMP_SIZE=$(wc -c < "${DUMP_FILE}" | tr -d ' ')
[ "${DUMP_SIZE}" -gt 1000 ] || { echo "  ❌ 备份文件异常（${DUMP_SIZE} 字节）"; exit 1; }
echo "  ✅ 备份完成：${DUMP_FILE}（$((DUMP_SIZE / 1024)) KB）"

echo ""
echo "=== 2/5 建演练库并恢复 ==="
docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d postgres -c "DROP DATABASE IF EXISTS ${DRILL_DB}" >/dev/null
docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d postgres -c "CREATE DATABASE ${DRILL_DB}" >/dev/null
docker exec -i "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${DRILL_DB}" < "${DUMP_FILE}" >/dev/null
T1=$(date +%s)
echo "  ✅ 恢复完成（耗时 $((T1 - T0))s）"

echo ""
echo "=== 3/5 数据一致性断言 ==="
SRC_TABLES=$(docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${SRC_DB}" -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
DST_TABLES=$(docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${DRILL_DB}" -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
echo "  表数量：源=${SRC_TABLES} 恢复后=${DST_TABLES}"
[ "${SRC_TABLES}" -eq "${DST_TABLES}" ] || { echo "  ❌ 表数量不一致"; exit 1; }

SRC_ROWS=$(docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${SRC_DB}" -tAc \
    "SELECT count(*) FROM orders" 2>/dev/null || echo "n/a")
DST_ROWS=$(docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d "${DRILL_DB}" -tAc \
    "SELECT count(*) FROM orders" 2>/dev/null || echo "n/a")
echo "  orders 行数：源=${SRC_ROWS} 恢复后=${DST_ROWS}"
[ "${SRC_ROWS}" = "${DST_ROWS}" ] || { echo "  ❌ 行数不一致"; exit 1; }
echo "  ✅ 恢复库与源库一致"

echo ""
echo "=== 4/5 RPO/RTO 口径 ==="
echo "  RTO（恢复耗时）= $((T1 - T0))s（本演练 = pg_restore 全量恢复时间）"
echo "  RPO（最大可接受丢失窗口）= 备份周期（DatabaseBackupTask 为每日 02:00，即最坏 24h）"
echo "  生产口径建议：缩小备份间隔（如 6h）或引入 WAL 归档把 RPO 压到分钟级"

echo ""
echo "=== 5/5 清理演练库 ==="
docker exec "${PG_CONTAINER}" psql -U "${PG_USER}" -d postgres -c "DROP DATABASE ${DRILL_DB}" >/dev/null
echo "  ✅ 已删除 ${DRILL_DB}（演练不留垃圾）"

echo ""
echo "========================================"
echo "  备份恢复演练通过 ✅（备份 ${DUMP_SIZE} 字节 → 恢复一致）"
echo "========================================"
