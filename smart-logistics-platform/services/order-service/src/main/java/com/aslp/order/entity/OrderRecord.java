package com.aslp.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 统一订单记录（M1）—— 各电商平台异构报文经 DTO 转换后的标准化落库实体。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code orderId} 全局唯一（平台单号），配合 {@code existsByOrderId} 实现幂等导入，防重复拉单。</li>
 *   <li>{@code errorTag} 承载「系统自动匹配失败 / 邮编错漏 / 地址不合规」等异常标签，支撑 M1 异常修正后台。</li>
 *   <li>{@code @Version} 乐观锁，防止客服远程修正与自动同步并发覆盖。</li>
 * </ul>
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_platform_status", columnList = "platform,status"),
        @Index(name = "idx_orders_warehouse", columnList = "warehouse_code")
})
public class OrderRecord {

    /** 异常标签：地址不合规。 */
    public static final String TAG_ADDRESS_INVALID = "ADDRESS_INVALID";
    /** 异常标签：邮编错漏。 */
    public static final String TAG_POSTCODE_MISSING = "POSTCODE_MISSING";
    /** 异常标签：系统自动匹配失败。 */
    public static final String TAG_MATCH_FAILED = "MATCH_FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, length = 64)
    private String orderId;

    /** 来源平台，如 Amazon-Mock / Amazon / eBay。 */
    @Column(nullable = false, length = 32)
    private String platform;

    @Column(length = 200)
    private String product;

    /** 履约仓库：Bruchsal（总仓）/ Mönchengladbach（分仓）。 */
    @Column(name = "warehouse_code", nullable = false, length = 50)
    private String warehouseCode;

    /** 订单状态：CREATED / PAID / PICKED / SHIPPED / FBA_RETURN_LABEL / DELIVERED / COMPLETED。 */
    @Column(nullable = false, length = 32)
    private String status;

    /** 异常标签（为空表示数据正常）。 */
    @Column(name = "error_tag", length = 100)
    private String errorTag;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private Long version;

    protected OrderRecord() {
        // JPA 需要无参构造
    }

    public OrderRecord(String orderId, String platform, String product, String warehouseCode, String status) {
        this.orderId = orderId;
        this.platform = platform;
        this.product = product;
        this.warehouseCode = warehouseCode;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public String getWarehouseCode() {
        return warehouseCode;
    }

    public void setWarehouseCode(String warehouseCode) {
        this.warehouseCode = warehouseCode;
    }

    public String getStatus() {
        return status;
    }

    /** 更新状态并刷新 updatedAt。 */
    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getErrorTag() {
        return errorTag;
    }

    public void setErrorTag(String errorTag) {
        this.errorTag = errorTag;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
