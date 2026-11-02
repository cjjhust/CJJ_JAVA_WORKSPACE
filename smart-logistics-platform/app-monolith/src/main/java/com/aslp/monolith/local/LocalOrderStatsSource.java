package com.aslp.monolith.local;

import com.aslp.order.repository.OrderRepository;
import com.aslp.report.client.OrderStatsSource;
import com.aslp.report.dto.OrderStatsSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 订单统计的<b>进程内</b>数据源（单体拓扑专用，P2-7）。
 *
 * <p>微服务拓扑下同一份数据走 {@code GET /api/orders/stats}（HTTP）；
 * 单体拓扑下直接注入 order 模块的 {@link OrderRepository}，
 * 统计口径（分组排序、null → UNKNOWN）与 {@code OrderPullController#stats()} 保持一致 ——
 * 那一段逻辑原本是 controller 的 private 方法，这里按同一规则复刻，
 * 并在 javadoc 双向引用，口径变更时两处必须同步。
 *
 * <p>失败语义：进程内调用不会超时，但数据库不可用仍可能抛异常，
 * 统一包成 {@link com.aslp.report.client.ReportSourceUnavailableException}
 * 让聚合方走同一条降级路径 —— 两种拓扑的看板行为因此完全一致。
 */
@Component
@Profile("monolith")
public class LocalOrderStatsSource implements OrderStatsSource {

    private final OrderRepository repository;

    public LocalOrderStatsSource(OrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public String getSource() {
        return "order-service";
    }

    @Override
    public OrderStatsSnapshot fetch() {
        try {
            return new OrderStatsSnapshot(
                    repository.count(),
                    repository.countByStatus("PAID"),
                    repository.countByStatus("SHIPPED"),
                    repository.countByStatus("COMPLETED"),
                    repository.countByErrorTagNotNull(),
                    groupToMap(repository.countGroupByStatus()),
                    groupToMap(repository.countGroupByWarehouseCode()),
                    groupToMap(repository.countGroupByErrorTag()));
        } catch (RuntimeException e) {
            throw new com.aslp.report.client.ReportSourceUnavailableException(getSource(), e);
        }
    }

    /** 与 {@code OrderPullController#groupToMap} 同口径：按键排序 + null 记 UNKNOWN。 */
    private static Map<String, Long> groupToMap(java.util.List<Object[]> rows) {
        Map<String, Long> sorted = new TreeMap<>();
        for (Object[] row : rows) {
            String key = row[0] == null ? "UNKNOWN" : String.valueOf(row[0]);
            Long count = row[1] == null ? 0L : ((Number) row[1]).longValue();
            sorted.put(key, count);
        }
        return new LinkedHashMap<>(sorted);
    }
}
