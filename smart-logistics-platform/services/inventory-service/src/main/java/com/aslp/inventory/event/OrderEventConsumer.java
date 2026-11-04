package com.aslp.inventory.event;

import com.aslp.inventory.service.OrderReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P2-3 — 订单同步事件消费者（`order.sync` → 幂等预占）。
 *
 * <p><b>错误处理分两类</b>（与项目其它失败三分类同一思想——先分「谁的错」再决定动作）：
 * <ul>
 *   <li><b>业务失败</b>（缺货 / 数量非法）：正常业务态，直接返回（ack，不重试）——
 *       重试也不会有货，重试只是把同一结果推迟几分钟；</li>
 *   <li><b>系统失败</b>（broker 反序列化、DB 连不上）：抛出 → 默认 error handler 按配置重试，
 *       耗尽后进 DLT（{@code order.sync.DLT}），等待人工 / 重放接口补偿 —— 防漏单的最后兜底。</li>
 * </ul>
 *
 * <p><b>为什么允许关闭</b>：本地 H2 单测 / 无 broker 环境下消费者启动会阻塞重试，
 * {@code aslp.kafka.consumer-enabled=false} 可整体关闭（compose 默认开启）。
 */
@Component
@ConditionalOnProperty(name = "aslp.kafka.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final OrderReservationService reservationService;

    /** 消费指标（Prometheus：消费总量 / 业务失败 / 系统失败）。 */
    private final AtomicLong consumed = new AtomicLong();
    private final AtomicLong businessFailures = new AtomicLong();
    private final AtomicLong systemFailures = new AtomicLong();

    public OrderEventConsumer(OrderReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @KafkaListener(topics = OrderEventPublisherHolder.TOPIC, groupId = "inventory-service")
    public void onOrderSync(@Header(KafkaHeaders.RECEIVED_KEY) String key, String payload) {
        consumed.incrementAndGet();
        try {
            OrderSyncEvent event = OrderSyncEvent.parse(payload);
            OrderReservationService.ReservationResult result =
                    reservationService.reserve(event.orderId(), event.sku(), event.warehouse(), event.quantity());

            switch (result.outcome()) {
                case DUPLICATE -> log.info("[订单消费] {} 重复事件，忽略", event.orderId());
                case OUT_OF_STOCK -> {
                    businessFailures.incrementAndGet();
                    log.warn("[订单消费] {} 预占失败（业务态，不重试）：{}", event.orderId(), result.detail());
                }
                case RESERVED -> log.info("[订单消费] {} 预占成功", event.orderId());
            }
        } catch (OrderSyncEvent.BadEventException e) {
            // 毒事件（无法解析）：重试无意义，直接进 DLT —— 抛出让 error handler 走 DLT 路径
            systemFailures.incrementAndGet();
            log.error("[订单消费] 事件无法解析（key={}），进 DLT：{}", key, e.getMessage());
            throw new PoisonEventException(key, payload, e);
        } catch (RuntimeException e) {
            // 系统失败（DB 连不上等）：抛出 → error handler 重试 → 耗尽进 DLT
            systemFailures.incrementAndGet();
            log.error("[订单消费] {} 处理失败（系统态，将重试后进 DLT）：{}", key, e.getMessage());
            throw e;
        }
    }

    /** 毒事件：跳过重试直达 DLT（自定义异常配合 error handler 的 exceptions 配置）。 */
    public static class PoisonEventException extends RuntimeException {
        public PoisonEventException(String key, String payload, Throwable cause) {
            super("poison event key=" + key + " payload=" + payload, cause);
        }
    }

    /** 消费计数（供 actuator 端点 / 指标断言）。 */
    public long consumedCount() {
        return consumed.get();
    }

    public long businessFailureCount() {
        return businessFailures.get();
    }

    public long systemFailureCount() {
        return systemFailures.get();
    }
}
