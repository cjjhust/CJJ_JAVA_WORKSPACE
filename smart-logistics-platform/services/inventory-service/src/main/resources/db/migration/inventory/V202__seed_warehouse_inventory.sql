-- =============================================================================
-- V2 — 两仓 SKU 种子数据（Bruchsal 总仓 + Mönchengladbach 分仓）
--
-- 幂等：WHERE NOT EXISTS，重复执行不会产生重复行。
--
-- ⚠️ version 必须显式为 0：
--    若留 NULL，Hibernate 的 @Version 会生成 `where version = null` 的 UPDATE，
--    永远匹配 0 行 → StaleStateException（库存扣减必失败）。
-- =============================================================================

INSERT INTO inventory (sku, warehouse_code, available_qty, locked_qty, unit_price, version)
SELECT v.sku, v.warehouse_code, v.available_qty, v.locked_qty, v.unit_price, 0
FROM (VALUES
    -- Bruchsal 总仓（高利润垂直品类主力仓）
    ('AMZ-1001',  'Bruchsal',          320, 12,  89.90),
    ('AMZ-1002',  'Bruchsal',          150,  5, 249.00),
    ('AMZ-1003',  'Bruchsal',           45,  0,  35.50),
    ('EBAY-2001', 'Bruchsal',          210,  8,  59.90),
    -- Mönchengladbach 分仓（大件中转 + 逆向退货换标）
    ('AMZ-1001',  'Mönchengladbach',    95,  3,  89.90),
    ('AMZ-1002',  'Mönchengladbach',    28,  2, 249.00),
    ('EBAY-2001', 'Mönchengladbach',    64,  0,  59.90),
    -- 低库存 SKU：触发 M2 安全库存预警（阈值 < 10）与补货邮件
    ('AMZ-9999',  'Mönchengladbach',     5,  0, 119.00)
) AS v(sku, warehouse_code, available_qty, locked_qty, unit_price)
WHERE NOT EXISTS (
    SELECT 1 FROM inventory i
    WHERE i.sku = v.sku AND i.warehouse_code = v.warehouse_code
);
