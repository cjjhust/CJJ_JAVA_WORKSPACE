package com.aslp.inventory.service;

import com.aslp.inventory.entity.OrderReservation;
import com.aslp.inventory.repository.OrderReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * P2-3 / P2-4 — 订单库存预占服务（幂等）。
 *
 * <p><b>幂等的三层保障</b>（与订单落库同一设计模式）：
 * <ol>
 *   <li>先查 {@code order_reservation} 是否已有该 orderId（绝大多数重复事件在此拦截）；</li>
 *   <li>插入时撞 {@code UNIQUE(order_id)} → {@code DataIntegrityViolationException} 捕获后
 *       视为"已处理"，返回成功 —— check-then-act 的竞态由数据库兜底；</li>
 *   <li>预占成功才扣减库存（可用 → 锁定，复用 {@link InventoryLockService} 的分布式锁 + 乐观锁）。</li>
 * </ol>
 *
 * <p><b>至少一次投递 + 幂等消费 = 有效恰好一次</b>：Kafka 不承诺恰好一次业务效果，
 * 幂等键（orderId）把重复投递变成无害的空操作。
 *
 * <p>库存不足时<b>不抛异常、返回失败状态</b>：缺货是正常业务态（超卖防护本来就挡它），
 * 与系统故障不同 —— 后者才进重试/DLT。
 */
@Service
public class OrderReservationService {

    private static final Logger log = LoggerFactory.getLogger(OrderReservationService.class);

    /** 预占结果。 */
    public enum Outcome { RESERVED, DUPLICATE, OUT_OF_STOCK }

    public record ReservationResult(Outcome outcome, String detail) {
    }

    private final OrderReservationRepository reservationRepository;
    private final InventoryLockService lockService;

    public OrderReservationService(OrderReservationRepository reservationRepository,
                                   InventoryLockService lockService) {
        this.reservationRepository = reservationRepository;
        this.lockService = lockService;
    }

    /**
     * 幂等预占：为订单扣减一件库存（可用 → 锁定）。
     *
     * @param orderId   平台订单号（幂等键）
     * @param sku       商品 SKU
     * @param warehouse 履约仓库
     * @param quantity  数量（必须为正）
     */
    @Transactional
    public ReservationResult reserve(String orderId, String sku, String warehouse, int quantity) {
        if (quantity <= 0) {
            return new ReservationResult(Outcome.OUT_OF_STOCK, "quantity must be positive: " + quantity);
        }

        // 1) 幂等检查：重复事件直接放行（返回成功，消费侧 ack，不会进重试）
        Optional<OrderReservation> existing = reservationRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("[预占] {} 已存在（status={}），重复事件忽略", orderId, existing.get().getStatus());
            return new ReservationResult(Outcome.DUPLICATE, "already reserved");
        }

        // 2) 扣减库存（Redisson 锁 + 乐观锁；库存不足返回 false —— 正常业务态）
        boolean deducted = lockService.deductInventory(sku, warehouse, quantity);
        if (!deducted) {
            log.warn("[预占] {} 库存不足：sku={} warehouse={} qty={}", orderId, sku, warehouse, quantity);
            return new ReservationResult(Outcome.OUT_OF_STOCK, "insufficient stock");
        }

        // 3) 落预占记录；UNIQUE(order_id) 是并发竞态的最终兜底
        try {
            OrderReservation reservation = new OrderReservation(orderId, sku, warehouse, quantity);
            reservationRepository.save(reservation);
            log.info("[预占] {} 成功：sku={} warehouse={} qty={}", orderId, sku, warehouse, quantity);
            return new ReservationResult(Outcome.RESERVED, "reserved");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发下另一消费者抢先落了同一 orderId：扣减要回滚吗？
            // —— 不回滚会双扣；但本方法在事务内，抛出异常让整个事务回滚（含扣减），
            //    然后向调用方报告 DUPLICATE（消费侧按成功 ack）。
            log.info("[预占] {} 并发插入冲突（UNIQUE(order_id)），事务回滚，按重复处理", orderId);
            throw new DuplicateReservationException(orderId, e);
        }
    }

    /** P2-4：支付确认 → 预占转 COMMITTED（库存保持锁定，进入履约）。 */
    @Transactional
    public boolean commit(String orderId) {
        return reservationRepository.findByOrderId(orderId)
                .map(r -> {
                    if (r.getStatus() != OrderReservation.Status.RESERVED) {
                        log.info("[预占] {} 状态为 {}，不能提交", orderId, r.getStatus());
                        return false;
                    }
                    r.setStatus(OrderReservation.Status.COMMITTED);
                    reservationRepository.save(r);
                    log.info("[预占] {} 已提交（COMMITTED）", orderId);
                    return true;
                })
                .orElse(false);
    }

    /** P2-4：超时未支付 / 订单取消 → 释放库存回可用。 */
    @Transactional
    public boolean release(String orderId) {
        return reservationRepository.findByOrderId(orderId)
                .map(r -> {
                    if (r.getStatus() != OrderReservation.Status.RESERVED) {
                        log.info("[预占] {} 状态为 {}，无需释放", orderId, r.getStatus());
                        return false;
                    }
                    boolean returned = lockService.releaseLockedInventory(
                            r.getSku(), r.getWarehouse(), r.getQuantity());
                    if (!returned) {
                        // 释放失败（锁定量不足等）：保留 RESERVED 让下轮任务重试，不掩盖
                        log.warn("[预占] {} 释放库存失败，保留 RESERVED 等待重试", orderId);
                        return false;
                    }
                    r.markReleased();
                    reservationRepository.save(r);
                    log.info("[预占] {} 已释放（RELEASED）", orderId);
                    return true;
                })
                .orElse(false);
    }

    /** P2-4：超时释放补偿任务 —— 扫描超时未提交的预占并释放。 */
    @Transactional
    public int releaseExpired(java.time.Duration maxAge) {
        Instant threshold = Instant.now().minus(maxAge);
        List<OrderReservation> expired =
                reservationRepository.findByStatusAndCreatedAtBefore(OrderReservation.Status.RESERVED, threshold);
        int released = 0;
        for (OrderReservation r : expired) {
            if (release(r.getOrderId())) {
                released++;
            }
        }
        return released;
    }

    /** 预占并发冲突：消费侧捕获后按 DUPLICATE 处理（事务已回滚，库存无双扣）。 */
    public static class DuplicateReservationException extends RuntimeException {
        public DuplicateReservationException(String orderId, Throwable cause) {
            super("reservation already exists: " + orderId, cause);
        }
    }
}
