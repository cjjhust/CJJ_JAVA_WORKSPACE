package com.aslp.inventory.repository;

import com.aslp.inventory.entity.OrderReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * P2-3 / P2-4 — 预占记录仓储。
 */
public interface OrderReservationRepository extends JpaRepository<OrderReservation, Long> {

    Optional<OrderReservation> findByOrderId(String orderId);

    /** 超时释放扫描：仍在预占状态、创建时间早于阈值。 */
    List<OrderReservation> findByStatusAndCreatedAtBefore(OrderReservation.Status status, java.time.Instant threshold);
}
