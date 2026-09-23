package com.aslp.order.repository;

import com.aslp.order.entity.OrderStateRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 订单状态机当前状态仓储（M4 持久化）。
 *
 * <p>主键是业务单号（{@code String}），因此不提供派生查询 —— {@code findById} / {@code deleteById}
 * 已经够用。事实源是数据库：服务重启后状态仍在（这是本轮改动要解决的核心问题）。
 */
@Repository
public interface OrderStateRepository extends JpaRepository<OrderStateRecord, String> {
}
