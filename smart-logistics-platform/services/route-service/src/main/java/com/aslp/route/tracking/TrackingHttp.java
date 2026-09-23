package com.aslp.route.tracking;

import com.aslp.route.config.TrackingProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 追踪客户端的 HTTP 装配（与 order-service 的 {@code SpApiHttp} 同一职责）。
 *
 * <p><b>为什么必须复用注入进来的 {@link RestClient.Builder}</b>：
 * Spring Boot 自动装配的 builder 上已经挂了可观测性与 W3C traceparent 透传。
 * 自己 {@code RestClient.builder()} 会丢掉它们 —— 于是"route-service → DHL"这段调用
 * 既不产生 span、也不会把 traceId 带给对方，P1-3 的跨服务链路在这里断掉。
 *
 * <p><b>超时必须显式设置</b>：JDK 默认读超时是"无限等待"。承运商接口僵死时，
 * 被占住的线程会拖垮整个 route-service（而 VRP 求解本身是 CPU 密集的，更需要保护线程池）。
 */
public final class TrackingHttp {

    private TrackingHttp() {
    }

    /** 建客户端：保留外部 builder 的既有配置，只补 baseUrl 与超时。 */
    public static RestClient build(RestClient.Builder builder, String baseUrl, TrackingProperties props) {
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(props))
                .build();
    }

    /** 统一超时的请求工厂。 */
    public static ClientHttpRequestFactory requestFactory(TrackingProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());
        return factory;
    }
}
