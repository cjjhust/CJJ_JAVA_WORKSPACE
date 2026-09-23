package com.aslp.order.repository;

import com.aslp.order.entity.OrderRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    // ── 分组统计（M5 报表看板的数据源）──────────────────────────────────────
    // 为什么在数据库侧 group by，而不是把全部订单捞进内存再分组：
    // 报表接口是「随时可被前端轮询」的只读接口，不能随订单量增长把堆内存打满。
    // 返回 Object[] 而不是自定义投影接口：聚合结果只是「键 → 计数」两列，
    // 定义 DTO 反而增加一个需要同步维护的类型。

    /** 各状态订单数：[status, count]。 */
    @Query("select o.status, count(o) from OrderRecord o group by o.status")
    List<Object[]> countGroupByStatus();

    /** 各履约仓订单数：[warehouseCode, count]。 */
    @Query("select o.warehouseCode, count(o) from OrderRecord o group by o.warehouseCode")
    List<Object[]> countGroupByWarehouseCode();

    /** 各异常标签的订单数（不含未打标）：[errorTag, count]。 */
    @Query("select o.errorTag, count(o) from OrderRecord o where o.errorTag is not null group by o.errorTag")
    List<Object[]> countGroupByErrorTag();
}
