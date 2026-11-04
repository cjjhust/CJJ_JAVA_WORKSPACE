package com.aslp.inventory.event;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * P2-3 — Kafka 消费错误处理装配（DLT 兜底）。
 *
 * <p><b>为什么不用注解重试（@RetryableTopic）</b>：项目 Kafka 是单节点 KRaft 演示集群，
 * DefaultErrorHandler + DeadLetterPublishingRecoverer 是同一能力且行为完全可解释——
 * 每条失败事件先重试 {@code max-attempts} 次（固定间隔 backoff-ms），
 * 耗尽后原样投递 {@code order.sync.DLT}（header 带原始 topic/partition/异常栈），防漏单。
 *
 * <p><b>毒事件直达 DLT</b>：{@link OrderEventConsumer.PoisonEventException}
 * 无法通过重试好转（解析不了的报文重试一万次也解析不了），配置
 * {@code addNotRetryableExceptions} 让它跳过重试直接进 DLT。
 */
@Configuration
public class KafkaErrorConfig {

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<Object, Object> template,
            @Value("${aslp.kafka.dlt.max-attempts:3}") long maxAttempts,
            @Value("${aslp.kafka.dlt.backoff-ms:1000}") long backoffMs) {

        // DLT 发布器：失败事件 → {topic}.DLT（order.sync → order.sync.DLT，与规划一致）
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(backoffMs, maxAttempts - 1));
        // 毒事件（解析失败）不重试，直达 DLT
        handler.addNotRetryableExceptions(com.aslp.inventory.event.OrderEventConsumer.PoisonEventException.class);
        return handler;
    }

    /** 监听容器工厂：挂上 DLT error handler（@KafkaListener 默认工厂）。 */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
