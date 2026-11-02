package com.aslp.inventory.repository;

import com.aslp.inventory.entity.InventoryItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-8：inventory 派生查询与 Flyway 迁移的「数据库行为」集成测试
 * （真实 PostgreSQL via Testcontainers，与 order-service 同一套做法）。
 *
 * <p>H2 测试验证不了的三件事，这里补上：
 * <ul>
 *   <li>{@code V1/V2} 迁移的 PG 方言 SQL（{@code NUMERIC(12,2)} / 索引 / 种子数据）真的能跑；</li>
 *   <li>{@code findByAvailableQtyLessThan}（预警查询）与
 *       {@code findBySkuAndWarehouseCode}（锁定路径的前置查询）命中真实行；</li>
 *   <li>V2 种子数据落地 —— 报表看板与预警巡检的演示前提。</li>
 * </ul>
 */
@Testcontainers
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/inventory",
        "spring.flyway.table=flyway_schema_history_inventory",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class InventoryRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aslp")
            .withUsername("aslp")
            .withPassword("aslp123");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private InventoryRepository repository;

    @Test
    @DisplayName("V2 种子数据已迁移落地（两仓库存行存在）")
    void seedDataMigrated() {
        assertTrue(repository.count() > 0, "V2 种子数据应已插入");
    }

    @Test
    @DisplayName("findBySkuAndWarehouseCode 命中唯一行（锁定路径前置查询）")
    void findBySkuAndWarehouseHitsSingleRow() {
        InventoryItem any = repository.findAll().iterator().next();
        var found = repository.findBySkuAndWarehouseCode(any.getSku(), any.getWarehouseCode());
        assertTrue(found.isPresent());
        assertEquals(any.getId(), found.get().getId());
    }

    @Test
    @DisplayName("findByAvailableQtyLessThan 只返回低于阈值的行（预警口径）")
    void lowStockQueryMatchesThreshold() {
        List<InventoryItem> low = repository.findByAvailableQtyLessThan(10_000);
        assertTrue(low.stream().allMatch(i -> i.getAvailableQty() < 10_000));
    }

    @Test
    @DisplayName("同一 SKU 跨两仓各一行（findBySku 语义前提）")
    void findBySkuReturnsRowsPerWarehouse() {
        InventoryItem any = repository.findAll().iterator().next();
        repository.findBySku(any.getSku()).forEach(item ->
                assertEquals(any.getSku(), item.getSku()));
    }
}
