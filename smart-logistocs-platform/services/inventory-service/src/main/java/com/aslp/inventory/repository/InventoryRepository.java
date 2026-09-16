package com.aslp.inventory.repository;

import com.aslp.inventory.entity.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {
    Optional<InventoryItem> findBySkuAndWarehouseCode(String sku, String warehouseCode);

    /**
     * 安全库存预警查询：按「可用库存」字段名派生（availableQty）。
     * 注意：方法名必须与实体属性一致，否则 Spring Data JPA 启动时抛 PropertyReferenceException。
     */
    List<InventoryItem> findByAvailableQtyLessThan(int threshold);
}
