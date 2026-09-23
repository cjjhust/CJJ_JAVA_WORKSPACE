package com.aslp.report.client;

import com.aslp.report.config.ReportProperties;
import com.aslp.report.dto.InventoryWarningSnapshot;
import com.aslp.report.dto.OrderStatsSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表下游客户端的契约测试（M5）。
 *
 * <p><b>为什么用 JDK 自带 {@link HttpServer} 起真服务，而不是 Mockito 打桩</b>：
 * 这里要验证的恰恰是"接线"本身 —— 请求路径对不对、JSON 能不能反序列化成 record、
 * 4xx/5xx 会不会被正确包成 {@code ReportSourceUnavailableException}。
 * 这些用 mock 全都验不到（mock 只是把我以为的答案再说一遍）。
 * 顺带也解释了为什么不用 Mockito：{@code RestClient} 是 final 风格的构建器 API，
 * 打桩成本高且验证力更弱。
 */
class ReportClientTest {

    private static HttpServer server;
    private static String baseUrl;

    /** 可变状态：让个别用例把订单接口切成 503（模拟上游故障）。 */
    private static volatile boolean orderEndpointFailing = false;

    private static final String ORDER_STATS_JSON = """
            {
              "total": 3,
              "paid": 2,
              "shipped": 1,
              "completed": 0,
              "withErrorTag": 0,
              "byStatus": {"CREATED": 1, "PAID": 2},
              "byWarehouse": {"Bruchsal": 2, "Mönchengladbach": 1},
              "byErrorTag": {},
              "unknownFutureField": 42
            }
            """;

    private static final String WARNING_JSON = """
            {
              "threshold": 10,
              "secondsUntilMailAllowed": 900,
              "lowStock": [
                {"sku": "AMZ-9999", "warehouse": "Mönchengladbach", "availableQty": 5, "lockedQty": 1}
              ],
              "someNewField": "ignored"
            }
            """;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/orders/stats", exchange -> {
            if (orderEndpointFailing) {
                respond(exchange, 503, "{\"error\":\"upstream busy\"}");
            } else {
                respond(exchange, 200, ORDER_STATS_JSON);
            }
        });
        server.createContext("/api/inventory/warnings/status",
                exchange -> respond(exchange, 200, WARNING_JSON));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        orderEndpointFailing = false;
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static ReportProperties props(String orderUrl, String inventoryUrl) {
        ReportProperties props = new ReportProperties();
        props.setOrderBaseUrl(orderUrl);
        props.setInventoryBaseUrl(inventoryUrl);
        return props;
    }

    @Test
    void orderStatsAreDeserializedAndKeepDistributionOrder() {
        OrderStatsSnapshot stats = new OrderStatsClient(RestClient.builder(), props(baseUrl, baseUrl)).fetch();

        assertEquals(3, stats.total());
        assertEquals(2, stats.paid());
        // 顺序必须保持上游 LinkedHashMap 的迭代顺序（图表颜色/顺序依赖它，见 OrderPullController.groupToMap）
        assertEquals(List.of("CREATED", "PAID"), List.copyOf(stats.byStatus().keySet()));
        assertEquals(1L, stats.byStatus().get("CREATED"));
        assertEquals(2L, stats.byStatus().get("PAID"));
        assertEquals(List.of("Bruchsal", "Mönchengladbach"), List.copyOf(stats.byWarehouse().keySet()));
    }

    @Test
    void unknownUpstreamFieldsDoNotBreakDeserialization() {
        // 上游新增字段（unknownFutureField / someNewField）必须被忽略：
        // 报表是只读消费方，不该因为上游加了一个字段就整体失败
        OrderStatsSnapshot stats = new OrderStatsClient(RestClient.builder(), props(baseUrl, baseUrl)).fetch();
        InventoryWarningSnapshot warning = new InventoryWarningClient(RestClient.builder(), props(baseUrl, baseUrl)).fetch();

        assertEquals(3, stats.total());
        assertEquals(10, warning.threshold());
    }

    @Test
    void inventoryWarningCarriesThresholdAndLowStockDetail() {
        InventoryWarningSnapshot snapshot =
                new InventoryWarningClient(RestClient.builder(), props(baseUrl, baseUrl)).fetch();

        assertEquals(10, snapshot.threshold());
        assertEquals(900, snapshot.secondsUntilMailAllowed());
        assertEquals(1, snapshot.lowStock().size());
        assertEquals("AMZ-9999", snapshot.lowStock().get(0).sku());
        assertEquals(5, snapshot.lowStock().get(0).availableQty());
    }

    @Test
    void upstream5xxIsWrappedAsSourceUnavailable() {
        orderEndpointFailing = true;
        try {
            OrderStatsClient client = new OrderStatsClient(RestClient.builder(), props(baseUrl, baseUrl));
            ReportSourceUnavailableException ex =
                    assertThrows(ReportSourceUnavailableException.class, client::fetch);
            // source 必须准确：看板要靠它告诉用户"是哪一块数据不可用"
            assertEquals("order-service", ex.getSource());
            assertTrue(ex.getMessage().contains("order-service"));
        } finally {
            orderEndpointFailing = false;
        }
    }

    @Test
    void unreachableSourceIsWrappedRatherThanLeakingTransportException() {
        // 端口 1 上不会有服务监听 —— 模拟"上游根本没起来"
        ReportProperties props = props("http://127.0.0.1:1", "http://127.0.0.1:1");

        ReportSourceUnavailableException ex = assertThrows(ReportSourceUnavailableException.class,
                () -> new InventoryWarningClient(RestClient.builder(), props).fetch());
        assertEquals("inventory-service", ex.getSource());
    }
}
