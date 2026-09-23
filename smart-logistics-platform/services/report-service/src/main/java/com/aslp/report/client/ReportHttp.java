package com.aslp.report.client;

import com.aslp.report.config.ReportProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 下游数据源 HTTP 客户端的装配工具（M5 报表）。
 *
 * <p><b>为什么每个客户端自己建 RestClient，而不是注入两个 {@code RestClient} bean</b>：
 * 容器里出现多个同类型 bean 时，注入点必须靠 {@code @Primary} 或限定符消歧，
 * 否则启动即失败（本项目已在网关 {@code KeyResolver} 上踩过同一坑，见 readme §9 #25）。
 * 报表只有两个下游、各自绑定不同的 baseUrl，用「各建各的」比引入 bean 消歧更不容易出错。
 *
 * <p><b>为什么必须复用 Spring Boot 自动装配的 {@link RestClient.Builder}</b>：
 * 它已经挂了可观测性（Observation + W3C traceparent 透传）。自己 {@code RestClient.builder()}
 * 会丢掉这些，于是「报表 → 订单服务」这段调用既没有 span、也不会把 traceId 带给下游
 * —— P1-3 的跨服务链路会在这里断掉（同一教训见 order-service 的 {@code SpApiHttp}）。
 */
public final class ReportHttp {

    private ReportHttp() {
    }

    /** 基于外部 builder 建客户端：只覆盖 baseUrl 与超时，其余（观测/转换器）保持自动装配。 */
    public static RestClient build(RestClient.Builder builder, String baseUrl, ReportProperties props) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(props))
                .build();
    }

    /** 统一超时的请求工厂：建连与读取都必须有上限，不能靠默认的「无限等待」。 */
    public static ClientHttpRequestFactory requestFactory(ReportProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());
        return factory;
    }
}
