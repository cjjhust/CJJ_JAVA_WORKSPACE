package com.aslp.order.strategy;

import com.aslp.order.spapi.SpApiOrderClient;
import com.aslp.order.spapi.SpApiOrderMapper;
import com.aslp.order.spapi.SpApiProperties;
import com.aslp.order.spapi.SpApiTestFixture;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon SP-API 策略的失败语义测试（P1-4）。
 *
 * <p>策略层是「平台异常分类」与「框架容错」的交界处，契约只有两条，本类逐条锁定：
 * <ol>
 *   <li><b>可重试的不可用</b>（429 / 5xx / 超时）必须<b>向上抛</b> —— 一旦被吞成
 *       {@code success=false}，P1-2 的重试与熔断就永远不触发，
 *       平台故障时会静默变成「拉取 0 单」；</li>
 *   <li><b>不可重试的失败</b>（缺凭据 / 4xx）必须转成 {@code success=false} 的结果，
 *       让 {@code POST /api/orders/pull} 返回 200 + 明确原因，而不是 500。</li>
 * </ol>
 */
class AmazonSpApiStrategyTest {

    @RegisterExtension
    static final WireMockExtension PLATFORM = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private SpApiProperties props;
    private SpApiOrderClient client;
    private SpApiOrderMapper mapper;

    @BeforeEach
    void setUp() {
        PLATFORM.resetAll();
        props = SpApiTestFixture.properties(PLATFORM);
        client = SpApiTestFixture.orderClient(props, SpApiTestFixture.tokenClient(props));
        mapper = SpApiTestFixture.mapper(props);
        PLATFORM.stubFor(post(urlEqualTo("/auth/o2/token"))
                .willReturn(okJson(SpApiTestFixture.tokenJson())));
    }

    private AmazonSpApiStrategy strategy() {
        return new AmazonSpApiStrategy(props, client, mapper);
    }

    @Test
    @DisplayName("平台标识为 Amazon（用于落库与日志区分）")
    void exposesPlatformName() {
        assertEquals("Amazon", strategy().getPlatformName());
    }

    @Test
    @DisplayName("未配置凭据：返回失败结果并给出原因，且不发任何 HTTP 请求")
    void returnsFailureResultWithoutCredentials() {
        props.setClientId(null);
        props.setClientSecret(null);

        OrderPullResult result = strategy().pullOrders();

        assertFalse(result.success());
        assertFalse(result.degraded(), "凭据缺失属业务性失败，不是容错降级");
        assertTrue(result.orders().isEmpty());
        assertEquals("Amazon", result.platform());
        assertNotNull(result.errorMsg());
        assertTrue(result.errorMsg().toLowerCase().contains("credential"),
                "错误信息需指向凭据缺失，便于运维定位");
        PLATFORM.verify(0, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
    }

    @Test
    @DisplayName("可重复调用：无副作用、结果稳定")
    void isIdempotentForRepeatedCalls() {
        props.setClientId(null);
        props.setClientSecret(null);

        OrderPullResult first = strategy().pullOrders();
        OrderPullResult second = strategy().pullOrders();

        assertEquals(first.success(), second.success());
        assertEquals(first.errorMsg(), second.errorMsg());
    }

    @Test
    @DisplayName("成功：官方订单映射为统一 DTO，success=true 且非降级")
    void returnsMappedOrdersOnSuccess() {
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(okJson(SpApiTestFixture.ordersPage(
                        SpApiTestFixture.orderJson("AMZ-1001", "Shipped", "Bruchsal", "DE"),
                        SpApiTestFixture.orderJson("AMZ-1002", "Pending", null, "DE")))));

        OrderPullResult result = strategy().pullOrders();

        assertTrue(result.success());
        assertFalse(result.degraded());
        assertEquals("Amazon", result.platform());
        assertEquals(2, result.orders().size());
        assertEquals("Bruchsal", result.orders().get(0).warehouse());
        assertFalse(result.orders().get(1).isNormal(), "缺收货城市的订单必须被打上异常标签");
    }

    @Test
    @DisplayName("429 限流：向上抛可重试异常（绝不吞成业务失败）")
    void propagatesRateLimitForResilience4j() {
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));

        assertThrows(PlatformUnavailableException.class, () -> strategy().pullOrders(),
                "429 必须抛出去，否则 P1-2 配置的重试/熔断永远不会触发");
    }

    @Test
    @DisplayName("400 契约错：转成 success=false 的业务失败（不抛，也不降级）")
    void convertsClientErrorToBusinessFailure() {
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(400).withBody("{\"errors\":[]}")));

        OrderPullResult result = strategy().pullOrders();

        assertFalse(result.success());
        assertFalse(result.degraded(), "4xx 是业务失败，不应被标记为「容错降级」（两者告警级别不同）");
        assertTrue(result.errorMsg().contains("400"));
    }

    @Test
    @DisplayName("分页被上限截断：success=true 但仍要显式提示结果可能不完整")
    void reportsTruncationWhileStillSucceeding() {
        props.setMaxPages(1);
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(okJson(SpApiTestFixture.ordersPageWithNext("ALWAYS-MORE",
                        SpApiTestFixture.orderJson("AMZ-1001", "Shipped", "Bruchsal", "DE")))));

        OrderPullResult result = strategy().pullOrders();

        assertTrue(result.success(), "部分数据也是有效数据：不该因为页数上限就判定失败");
        assertEquals(1, result.orders().size());
        assertNotNull(result.errorMsg(), "必须留下「可能还有未拉取订单」的痕迹");
        assertTrue(result.errorMsg().contains("分页上限"));
    }
}
