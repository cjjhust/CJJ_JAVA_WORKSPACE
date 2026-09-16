package com.aslp.inventory.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "inventory", indexes = {
    @Index(name = "idx_inventory_sku_warehouse", columnList = "sku,warehouse_code")
})
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String sku;

    @Column(name = "warehouse_code", nullable = false, length = 50)
    private String warehouseCode; // Bruchsal / Mönchengladbach

    @Column(name = "available_qty", nullable = false)
    private Integer availableQty = 0;

    @Column(name = "locked_qty", nullable = false)
    private Integer lockedQty = 0;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Version
    private Long version; // 乐观锁

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getWarehouseCode() { return warehouseCode; }
    public void setWarehouseCode(String warehouseCode) { this.warehouseCode = warehouseCode; }
    public Integer getAvailableQty() { return availableQty; }
    public void setAvailableQty(Integer availableQty) { this.availableQty = availableQty; }
    public Integer getLockedQty() { return lockedQty; }
    public void setLockedQty(Integer lockedQty) { this.lockedQty = lockedQty; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
