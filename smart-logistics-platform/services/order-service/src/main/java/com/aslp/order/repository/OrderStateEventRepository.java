package com.aslp.order.repository;

import com.aslp.order.entity.OrderStateEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 订单状态机事件轨迹仓储（M4 持久化，只写与读，不修改）。
 *
 * <p>按自增主键升序返回：同一毫秒内的多条事件靠 id 定序（时间戳做不到），
 * 这样「事件轨迹」在任何情况下都是稳定的因果顺序。
 */
@Repository
public interface OrderStateEventRepository extends JpaRepository<OrderStateEventRecord, Long> {

    List<OrderStateEventRecord> findByOrderIdOrderByIdAsc(String orderId);
}
