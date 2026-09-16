package com.aslp.order.service;

import com.aslp.order.entity.OrderRecord;
import com.aslp.order.repository.OrderRepository;
import com.aslp.order.strategy.OrderDto;
import com.aslp.order.strategy.OrderPullResult;
import com.aslp.order.strategy.OrderPullStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 订单统一接入服务（M1）—— 策略拉取 → DTO 标准化 → 幂等落库 → 异常打标。
 *
 * <p>幂等保障：以平台单号 {@code orderId} 为唯一键，重复拉取只更新不新增，
 * 从而支撑「定时轮询 + 手动补拉」并存而不会产生重复订单。
 */
@Service
public class OrderPullService {

    private static final Logger log = LoggerFactory.getLogger(OrderPullService.class);

    /** 仓库编码归一化：兼容 API 返回的无变音符写法，统一为业务展示口径。 */
    private static final Map<String, String> WAREHOUSE_ALIASES = Map.of(
            "bruchsal", "Bruchsal",
            "moenchengladbach", "Mönchengladbach",
            "monchengladbach", "Mönchengladbach",
            "mönchengladbach", "Mönchengladbach"
    );

    private final OrderPullStrategy strategy;
    private final OrderRepository repository;

    public OrderPullService(OrderPullStrategy strategy, OrderRepository repository) {
        this.strategy = strategy;
        this.repository = repository;
    }

    /** 单次拉取结果摘要。 */
    public record PullSummary(
            String platform,
            boolean success,
            int fetched,
            int created,
            int updated,
            int flagged,
            String errorMsg
    ) {
    }

    /**
     * 执行一次拉取并落库。
     *
     * <p>整体在一个事务内完成，避免「部分订单入库」的中间态。
     */
    @Transactional
    public PullSummary pullAndPersist() {
        OrderPullResult result = strategy.pullOrders();

        if (!result.success()) {
            log.warn("[订单接入] 平台 {} 拉取失败：{}", result.platform(), result.errorMsg());
            return new PullSummary(result.platform(), false, 0, 0, 0, 0, result.errorMsg());
        }

        int created = 0;
        int updated = 0;
        int flagged = 0;

        for (OrderDto dto : result.orders()) {
            String warehouse = normalizeWarehouse(dto.warehouse());
            Optional<OrderRecord> existing = repository.findByOrderId(dto.orderId());

            if (existing.isPresent()) {
                // 已存在：仅同步状态与异常标签（保留客服手工修正过的仓库编码）
                OrderRecord record = existing.get();
                record.setStatus(dto.status());
                record.setErrorTag(dto.errorTag());
                repository.save(record);
                updated++;
            } else {
                OrderRecord record = new OrderRecord(
                        dto.orderId(), result.platform(), dto.product(), warehouse, dto.status());
                record.setErrorTag(dto.errorTag());
                repository.save(record);
                created++;
            }

            if (!dto.isNormal()) {
                flagged++;
            }
        }

        log.info("[订单接入] 平台 {}：拉取 {} 条，新增 {}，更新 {}，异常打标 {}",
                result.platform(), result.orders().size(), created, updated, flagged);

        return new PullSummary(result.platform(), true,
                result.orders().size(), created, updated, flagged, null);
    }

    /** 按订单号查询。 */
    @Transactional(readOnly = true)
    public OrderRecord findByOrderId(String orderId) {
        return repository.findByOrderId(orderId).orElse(null);
    }

    /** 客服修正异常订单：更新仓库编码并清除异常标签（M1 任务 1.3）。 */
    @Transactional
    public boolean correctOrder(String orderId, String warehouseCode, String status) {
        return repository.findByOrderId(orderId)
                .map(record -> {
                    if (warehouseCode != null && !warehouseCode.isBlank()) {
                        record.setWarehouseCode(normalizeWarehouse(warehouseCode));
                    }
                    if (status != null && !status.isBlank()) {
                        record.setStatus(status);
                    }
                    record.setErrorTag(null);
                    repository.save(record);
                    log.info("[订单修正] {} 已修正为 warehouse={}, status={}",
                            orderId, record.getWarehouseCode(), record.getStatus());
                    return true;
                })
                .orElse(false);
    }

    private String normalizeWarehouse(String raw) {
        if (raw == null) {
            return null;
        }
        return WAREHOUSE_ALIASES.getOrDefault(raw.trim().toLowerCase(), raw.trim());
    }
}
