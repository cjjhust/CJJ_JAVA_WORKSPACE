package com.aslp.order.event;

import com.aslp.order.entity.OrderRecord;
import com.aslp.order.strategy.OrderDto;
import com.aslp.order.strategy.OrderPullResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P2-3 — 订单同步事件发布器（`order.sync` topic）。
 *
 * <p>职责边界：只把「新落库的订单」广播为事件，供 inventory-service 消费做库存预占。
 * 订单的主数据仍在 `orders` 表（平台是权威源，HTTP 同步链路不变）——
 * Kafka 在这里是<b>增量联动通道</b>，不是数据存储：事件丢了/重了，下游靠
 * 幂等预占（同一 orderId 只预占一次）与 DLT 重放兜底，不靠它回放全量。
 *
 * <p>发布失败不抛出：拉取落库已经成功，事件通道故障不应让整次同步失败
 * （HTTP 同步是主链路，事件是增强联动）。失败记 WARN + 指标，靠重放接口补偿。
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    /** 事件 topic（与 P2-3 规划一致；单分区——同订单事件保序即可，暂无需并行度）。 */
    public static final String TOPIC = "order.sync";

    /** 死信 topic：消费端反复失败的事件落这里，等待重放（防漏单）。 */
    public static final String DLT_TOPIC = "order.sync.DLT";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean enabled;

    public OrderEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               @Value("${aslp.kafka.enabled:true}") boolean enabled) {
        this.kafkaTemplate = kafkaTemplate;
        this.enabled = enabled;
    }

    /**
     * 把本次拉取新创建（created）的订单发为事件。
     * 只有 created 才发：updated 表示库存侧早就见过这个订单（幂等预占的键已存在），再发是纯噪音。
     */
    public void publishCreated(OrderPullResult result, List<OrderDto> createdDtos) {
        if (!enabled || createdDtos.isEmpty()) {
            return;
        }
        for (OrderDto dto : createdDtos) {
            String payload = toPayload(dto);
            // key = orderId：同订单事件进同一分区，天然保序（Kafka 只保证分区内有序）
            kafkaTemplate.send(TOPIC, dto.orderId(), payload)
                    .whenComplete((rec, ex) -> {
                        if (ex != null) {
                            log.warn("[订单事件] {} 发布失败（HTTP 主链路不受影响，等待重放）：{}",
                                    dto.orderId(), ex.getMessage());
                        }
                    });
        }
        log.info("[订单事件] 已发布 {} 条 order.sync 事件（platform={}）", createdDtos.size(), result.platform());
    }

    /** 从已落库实体构建事件（重放接口用）。 */
    public void publishRecord(OrderRecord record) {
        if (!enabled) {
            return;
        }
        OrderDto dto = new OrderDto(record.getOrderId(), record.getProduct(),
                record.getWarehouseCode(), record.getStatus(), record.getErrorTag());
        kafkaTemplate.send(TOPIC, dto.orderId(), toPayload(dto));
    }

    /** 事件体：JSON 单行（预占所需最小字段集，加 version 便于后续演进）。 */
    static String toPayload(OrderDto dto) {
        return "{\"orderId\":\"" + jsonEscape(dto.orderId()) + "\""
                + ",\"product\":\"" + jsonEscape(dto.product()) + "\""
                + ",\"warehouse\":\"" + jsonEscape(dto.warehouse()) + "\""
                + ",\"status\":\"" + jsonEscape(dto.status()) + "\""
                + ",\"errorTag\":" + (dto.errorTag() == null ? "null" : "\"" + jsonEscape(dto.errorTag()) + "\"")
                + ",\"schemaVersion\":1}";
    }

    private static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
