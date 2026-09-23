package com.aslp.report.client;

import com.aslp.report.config.ReportProperties;
import com.aslp.report.dto.OrderStatsSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 订单统计客户端（M5 报表的数据源之一）。
 *
 * <p><b>为什么走 HTTP 而不是直接查 orders 表</b>：报表服务不是订单数据的属主。
 * 即便当前两个服务共用同一个 PostgreSQL 实例（「分布式单体」的典型特征，
 * 见 readme §10），接口层也必须守住边界 —— 一旦报表直接查表，
 * 订单表任何一次结构调整都会变成报表服务的线上故障，而它甚至不知道自己耦合了。
 * 这条边界也是未来把数据库拆开时唯一不需要重写的部分。
 */
@Component
public class OrderStatsClient {

    /** 数据源标识：会原样出现在报表响应的 {@code unavailable} 列表里。 */
    public static final String SOURCE = "order-service";

    private final RestClient client;

    public OrderStatsClient(RestClient.Builder builder, ReportProperties props) {
        this.client = ReportHttp.build(builder, props.getOrderBaseUrl(), props);
    }

    /**
     * 拉取订单统计。
     *
     * @throws ReportSourceUnavailableException 连接失败 / 超时 / 4xx / 5xx / 空响应体
     */
    public OrderStatsSnapshot fetch() {
        try {
            OrderStatsSnapshot snapshot = client.get()
                    .uri("/api/orders/stats")
                    .retrieve()
                    .body(OrderStatsSnapshot.class);
            if (snapshot == null) {
                throw new IllegalStateException("响应体为空");
            }
            return snapshot;
        } catch (RestClientException | IllegalStateException e) {
            // 统一包成「数据源不可用」：调用方只需处理一种异常，
            // 由它决定降级（而不是让报表 500）。
            throw new ReportSourceUnavailableException(SOURCE, e);
        }
    }
}
