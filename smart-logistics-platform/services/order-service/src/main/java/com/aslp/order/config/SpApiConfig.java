package com.aslp.order.config;

import com.aslp.order.spapi.LwaTokenClient;
import com.aslp.order.spapi.SpApiOrderClient;
import com.aslp.order.spapi.SpApiOrderMapper;
import com.aslp.order.spapi.SpApiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * P1-4：Amazon SP-API 客户端的装配。
 *
 * <p>三个 bean 的生命周期与 {@code aslp.order.mock-enabled} <b>无关</b> —— 无论当前
 * 走 Mock 策略还是真实策略，诊断探针（{@code POST /api/orders/spapi/probe}）都能拿到
 * 真实 HTTP 客户端来验证「对外契约是否还通」。这是故意的：
 * 平台接口契约失效是<b>最需要提前发现</b>的故障，不应该被「是否启用真实策略」挡住。
 *
 * <p>注意注入的是 Spring Boot 自动装配的 {@link RestClient.Builder}
 * （已在容器内挂好 Observation / 链路透传），不是自己 new 的 builder —— 否则
 * 调用平台这一段在 Zipkin 里会凭空断掉。
 */
@Configuration
@EnableConfigurationProperties(SpApiProperties.class)
public class SpApiConfig {

    @Bean
    public LwaTokenClient lwaTokenClient(SpApiProperties properties, RestClient.Builder builder) {
        return new LwaTokenClient(properties, builder);
    }

    @Bean
    public SpApiOrderMapper spApiOrderMapper(SpApiProperties properties) {
        return new SpApiOrderMapper(properties);
    }

    @Bean
    public SpApiOrderClient spApiOrderClient(SpApiProperties properties, LwaTokenClient tokenClient,
                                             RestClient.Builder builder) {
        return new SpApiOrderClient(properties, tokenClient, builder);
    }
}
