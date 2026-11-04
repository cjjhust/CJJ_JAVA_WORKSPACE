package com.aslp.inventory.controller;

import com.aslp.inventory.entity.OrderReservation;
import com.aslp.inventory.repository.OrderReservationRepository;
import com.aslp.inventory.service.OrderReservationService;
import com.aslp.inventory.event.OrderEventPublisherHolder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P2-3 / P2-4 — 预占查询、DLT 重放与超时释放端点。
 *
 * <p><b>重放接口（防漏单）</b>：DLT 里的事件不会自动重试——那会变成无限循环。
 * 运维确认问题修复后，调本端点把 DLT 事件重新投回主 topic；
 * 幂等预占保证重复投递无害。
 */
@RestController
@RequestMapping("/api/inventory/reservations")
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

    private final OrderReservationRepository repository;
    private final OrderReservationService reservationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final org.springframework.kafka.core.ConsumerFactory<String, String> consumerFactory;
    private final MeterRegistry meterRegistry;
    private final long dltMaxPoll;

    public ReservationController(OrderReservationRepository repository,
                                 OrderReservationService reservationService,
                                 KafkaTemplate<String, String> kafkaTemplate,
                                 org.springframework.kafka.core.ConsumerFactory<String, String> consumerFactory,
                                 MeterRegistry meterRegistry,
                                 @Value("${aslp.kafka.dlt.replay-max-poll:50}") long dltMaxPoll) {
        this.repository = repository;
        this.reservationService = reservationService;
        this.kafkaTemplate = kafkaTemplate;
        this.consumerFactory = consumerFactory;
        this.meterRegistry = meterRegistry;
        this.dltMaxPoll = dltMaxPoll;
    }

    /** 预占记录查询（运维核对）。 */
    @GetMapping
    public List<OrderReservation> list(@RequestParam(required = false) String orderId) {
        if (orderId != null && !orderId.isBlank()) {
            return repository.findByOrderId(orderId).map(List::of).orElse(List.of());
        }
        return repository.findAll();
    }

    /**
     * DLT 重放：把 order.sync.DLT 中的事件（最多 max-poll 条）重新投回 order.sync。
     *
     * @return 每条重放事件的处理结果（DLT 事件体内含原始异常栈 header 之外的原始 payload）
     */
    @PostMapping("/dlt/replay")
    public ResponseEntity<Map<String, Object>> replayDlt() {
        Map<String, Object> result = new HashMap<>();
        long replayed = 0;
        long remaining = 0;
        try (var consumer = consumerFactory.createConsumer("dlt-replay", "dlt-replay-" + System.nanoTime())) {
            // 用 KafkaTemplate 附带的 consumer factory 建一次性 consumer 拉 DLT
            var partitions = consumer.partitionsFor(OrderEventPublisherHolder.DLT_TOPIC);
            if (partitions != null && !partitions.isEmpty()) {
                consumer.assign(partitions.stream()
                        .map(p -> new org.apache.kafka.common.TopicPartition(
                                OrderEventPublisherHolder.DLT_TOPIC, p.partition()))
                        .toList());
                consumer.seekToBeginning(consumer.assignment());
                var records = consumer.poll(Duration.ofSeconds(5));
                for (var record : records) {
                    if (replayed >= dltMaxPoll) {
                        remaining++;
                        continue;
                    }
                    kafkaTemplate.send(OrderEventPublisherHolder.TOPIC, record.key(), record.value());
                    replayed++;
                }
                if (remaining == 0 && records.count() == 0) {
                    // DLT 为空：正常态
                }
            }
        } catch (Exception e) {
            log.warn("[DLT 重放] 失败：{}", e.getMessage());
            result.put("error", e.getMessage());
            return ResponseEntity.status(503).body(result);
        }
        result.put("replayed", replayed);
        result.put("remainingDlt", remaining);
        meterRegistry.counter("aslp.order.dlt.replayed").increment(replayed);
        log.info("[DLT 重放] 重投 {} 条事件到 {}", replayed, OrderEventPublisherHolder.TOPIC);
        return ResponseEntity.ok(result);
    }

    /** P2-4 手动触发超时释放（运维兜底，平时由定时任务跑）。 */
    @PostMapping("/release-expired")
    public Map<String, Object> releaseExpired(
            @Value("${aslp.reservation.max-age-minutes:30}") long maxAgeMinutes) {
        int released = reservationService.releaseExpired(Duration.ofMinutes(maxAgeMinutes));
        Map<String, Object> result = new HashMap<>();
        result.put("released", released);
        result.put("maxAgeMinutes", maxAgeMinutes);
        return result;
    }
}
