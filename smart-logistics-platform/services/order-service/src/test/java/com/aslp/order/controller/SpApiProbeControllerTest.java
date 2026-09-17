package com.aslp.order.controller;

import com.aslp.order.spapi.LwaTokenClient;
import com.aslp.order.spapi.SpApiOrderClient;
import com.aslp.order.spapi.SpApiOrderMapper;
import com.aslp.order.spapi.SpApiProperties;
import com.aslp.order.spapi.SpApiTestFixture;
import com.aslp.order.strategy.OrderDto;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4 诊断探针的契约测试。
 *
 * <p>探针的职责是把「客户端的异常分类」翻译成<b>可断言的诊断报文</b>：
 * 端到端脚本只需要 grep 一个字段（{@code errorType} / {@code retryable}），
 * 就能判断「这条故障到底该重试还是该告警」。所以这里逐字段验证报文形状，
 * 避免线上突然少一个字段导致冒烟断言静静地失真。
 */
@DisplayName("P1-4 平台契约探针：诊断报文形状与分支覆盖")
class SpApiProbeControllerTest {

    @RegisterExtension
    static final WireMockExtension PLATFORM = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private SpApiProperties props;
    private LwaTokenClient tokens;
    private SpApiOrderMapper mapper;
    private SpApiOrderClient client;
    private SpApiProbeController controller;

    @BeforeEach
    void setUp() {
        PLATFORM.resetAll();
        props = SpApiTestFixture.properties(PLATFORM);
        tokens = SpApiTestFixture.tokenClient(props);
        mapper = SpApiTestFixture.mapper(props);
        client = SpApiTestFixture.orderClient(props, tokens);
        controller = new SpApiProbeController(props, client, mapper, tokens);
    }

    @Test
    @DisplayName("正常场景：报出页数、条数与映射后的订单（含仓库与商品名）")
    void reportsSuccessfulFetch() {
        stubToken();
        props.setFetchItemTitles(true);
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .withQueryParam("NextToken", com.github.tomakehurst.wiremock.client.WireMock.absent())
                .willReturn(okJson(SpApiTestFixture.ordersPageWithNext("PAGE-2",
                        SpApiTestFixture.orderJson("AMZ-1", "Shipped", "Bruchsal", "DE")))));
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .withQueryParam("NextToken", equalTo("PAGE-2"))
                .willReturn(okJson(SpApiTestFixture.ordersPage(
                        SpApiTestFixture.orderJson("AMZ-2", "Pending", "Moenchengladbach", "DE")))));
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders/AMZ-1/orderItems"))
                .willReturn(okJson("{\"payload\":{\"OrderItems\":[{\"Title\":\"AeroSleep 婴儿床\"}]}}")));
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders/AMZ-2/orderItems"))
                .willReturn(okJson("{\"payload\":{\"OrderItems\":[{\"Title\":\"折叠婴儿推车\"}]}}")));

        Map<String, Object> body = controller.probe(null).getBody();

        assertNotNull(body);
        assertEquals(true, body.get("ok"));
        assertEquals(2, body.get("pages"));
        assertEquals(false, body.get("truncated"));
        assertEquals(2, body.get("count"));
        assertEquals("A1PA6795UKMFR9", body.get("marketplaceId"),
                "未指定站点时应回落到配置里的默认站点");

        @SuppressWarnings("unchecked")
        List<OrderDto> orders = (List<OrderDto>) body.get("orders");
        assertEquals("Bruchsal", orders.get(0).warehouse());
        assertEquals("Mönchengladbach", orders.get(1).warehouse());
        assertEquals("AeroSleep 婴儿床", orders.get(0).product(), "探针要能证明「商品明细」这条契约也通");
        assertTrue(body.containsKey("elapsedMs"));
    }

    @Test
    @DisplayName("429：报出可重试 + Retry-After（运维据此决定退避而不是报警）")
    void reportsRateLimitAsRetryable() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7")));

        Map<String, Object> body = controller.probe(null).getBody();

        assertNotNull(body);
        assertEquals(false, body.get("ok"));
        assertEquals("RateLimitedException", body.get("errorType"));
        assertEquals(true, body.get("retryable"));
        assertEquals(7L, body.get("retryAfterSeconds"));
        assertFalse(body.containsKey("orders"), "失败报文不应混入空 orders 列表（会让断言含义模糊）");
    }

    @Test
    @DisplayName("400：报出不可重试（需要改代码/改授权，而不是加超时）")
    void reportsClientErrorAsNonRetryable() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(400).withBody("{\"errors\":[]}")));

        Map<String, Object> body = controller.probe(null).getBody();

        assertNotNull(body);
        assertEquals("SpApiClientException", body.get("errorType"));
        assertEquals(false, body.get("retryable"));
        assertNotNull(body.get("errorMsg"));
    }

    @Test
    @DisplayName("超时：报出可重试（PlatformUnavailableException 家族）")
    void reportsTimeoutAsRetryable() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(okJson(SpApiTestFixture.ordersPage(
                        SpApiTestFixture.orderJson("AMZ-1", "Shipped", "Bruchsal", "DE")))
                        .withFixedDelay(1500)));

        Map<String, Object> body = controller.probe(null).getBody();

        assertNotNull(body);
        assertEquals("PlatformUnavailableException", body.get("errorType"));
        assertEquals(true, body.get("retryable"));
        assertTrue(String.valueOf(body.get("errorMsg")).contains("超时"));
    }

    @Test
    @DisplayName("缺凭据：不发任何请求，直接报不可重试（避免误打真实平台）")
    void reportsMissingCredentialsWithoutHttpCall() {
        SpApiProperties noCreds = SpApiTestFixture.properties(PLATFORM);
        noCreds.setClientId(null);
        noCreds.setRefreshToken(null);
        LwaTokenClient noCredsTokens = SpApiTestFixture.tokenClient(noCreds);
        SpApiProbeController noCredsController = new SpApiProbeController(noCreds,
                SpApiTestFixture.orderClient(noCreds, noCredsTokens),
                SpApiTestFixture.mapper(noCreds), noCredsTokens);

        ResponseEntity<Map<String, Object>> response = noCredsController.probe(null);
        Map<String, Object> body = response.getBody();

        assertNotNull(body);
        assertEquals(false, body.get("configured"));
        assertEquals(SpApiProbeController.TYPE_CREDENTIALS_MISSING, body.get("errorType"));
        assertEquals(false, body.get("retryable"));
        assertTrue(String.valueOf(body.get("errorMsg")).contains("client-id"));
        assertEquals(200, response.getStatusCode().value(),
                "探针的「失败」是业务信息，HTTP 应恒为 200（否则监控会把它当接口故障）");

        PLATFORM.verify(0, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
        PLATFORM.verify(0, postRequestedFor(urlEqualTo("/auth/o2/token")));
    }

    @Test
    @DisplayName("站点参数可覆盖：不同 MarketplaceId 命中不同契约场景")
    void marketplaceIdCanBeOverridden() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .withQueryParam("MarketplaceIds", equalTo("AMZN-RATE-LIMITED"))
                .willReturn(aResponse().withStatus(429)));

        Map<String, Object> body = controller.probe("AMZN-RATE-LIMITED").getBody();

        assertNotNull(body);
        assertEquals("AMZN-RATE-LIMITED", body.get("marketplaceId"));
        assertEquals("RateLimitedException", body.get("errorType"));
        assertEquals(1L, body.get("retryAfterSeconds"), "缺失 Retry-After 时应兜底 1s");
    }

    private void stubToken() {
        PLATFORM.stubFor(post(urlEqualTo("/auth/o2/token"))
                .willReturn(okJson(SpApiTestFixture.tokenJson())));
    }
}
