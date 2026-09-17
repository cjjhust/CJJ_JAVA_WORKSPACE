package com.aslp.order.spapi;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * SP-API 的 HTTP 客户端装配（P1-4）。
 *
 * <p><b>为什么单独抽出来</b>：客户端有两处（订单接口、LWA 令牌接口），超时策略必须一致 ——
 * 否则会出现「订单接口有超时、换令牌没有超时」这种死角：令牌端点是外部依赖，
 * 一旦它僵死，连熔断器都还没轮上，线程就已经被占满了。
 *
 * <p><b>为什么复用外部传入的 builder</b>：Spring Boot 自动装配的 {@link RestClient.Builder}
 * 上已经挂了可观测性（Observation + 链路透传），自己 {@code RestClient.builder()} 会丢掉
 * span —— 这正是 P1-3「跨服务同一 traceId」能成立的原因，不能在这里断链。
 */
public final class SpApiHttp {

    private SpApiHttp() {
    }

    /** 给外部 builder 装上显式超时（不改动其已有配置，如观测/消息转换器）。 */
    public static RestClient.Builder configure(RestClient.Builder builder, SpApiProperties props) {
        return builder.requestFactory(requestFactory(props));
    }

    /** 统一超时的请求工厂：建连与读取都<b>必须</b>有上限。 */
    public static ClientHttpRequestFactory requestFactory(SpApiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(props.getReadTimeout());
        return factory;
    }
}
