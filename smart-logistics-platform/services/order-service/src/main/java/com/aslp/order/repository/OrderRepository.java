package com.aslp.order.repository;

import com.aslp.order.entity.OrderRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 订单仓储（M1）。
 *
 * <p>派生查询方法名必须与 {@link OrderRecord} 属性严格一致，否则 Spring Data JPA
 * 启动即抛 {@code PropertyReferenceException}（本项目 inventory-service 曾踩此坑）。
 */
@Repository
public interface OrderRepository extends JpaRepository<OrderRecord, Long> {

    Optional<OrderRecord> findByOrderId(String orderId);

    boolean existsByOrderId(String orderId);

    long countByStatus(String status);

    long countByErrorTagNotNull();

    Page<OrderRecord> findByStatus(String status, Pageable pageable);

    Page<OrderRecord> findByWarehouseCode(String warehouseCode, Pageable pageable);

    Page<OrderRecord> findByStatusAndWarehouseCode(String status, String warehouseCode, Pageable pageable);
}
