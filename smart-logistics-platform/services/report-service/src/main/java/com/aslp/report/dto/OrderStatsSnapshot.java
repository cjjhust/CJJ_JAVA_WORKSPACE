package com.aslp.report.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单侧的统计快照（order-service {@code GET /api/orders/stats} 的响应体）。
 *
 * <p><b>为什么单独定义一份 record，而不是直接返回 {@code Map}</b>：
 * 上游字段改名时，反序列化会立刻在启动/调用路径上暴露（强类型契约），
 * 而不是让报表悄悄少画一张图。这也是「聚合服务只认契约、不认对方数据库」的体现。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：上游新增字段（如以后加
 * {@code byCarrier}）不应让报表服务反序列化失败 —— 报表是只读消费方，应当前向兼容。
 *
 * <p>紧凑构造器里做 null → 空 map 的兜底：Jackson 对「字段存在但值为 null」会传 null 进来，
 * 而下游图表代码会直接遍历这些 map。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderStatsSnapshot(
        long total,
        long paid,
        long shipped,
        long completed,
        long withErrorTag,
        Map<String, Long> byStatus,
        Map<String, Long> byWarehouse,
        Map<String, Long> byErrorTag) {

    public OrderStatsSnapshot {
        byStatus = ordered(byStatus);
        byWarehouse = ordered(byWarehouse);
        byErrorTag = ordered(byErrorTag);
    }

    /** 兜底快照：订单数据源不可用时使用（全部为 0 / 空分布）。 */
    public static OrderStatsSnapshot unavailable() {
        return new OrderStatsSnapshot(0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
    }

    private static Map<String, Long> ordered(Map<String, Long> source) {
        return source == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
