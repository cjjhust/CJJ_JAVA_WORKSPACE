package com.aslp.order.spapi;

import com.aslp.order.strategy.OrderDto;
import com.aslp.order.strategy.PlatformUnavailableException;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.aslp.order.spapi.SpApiTestFixture.orderJson;
import static com.aslp.order.spapi.SpApiTestFixture.ordersPage;
import static com.aslp.order.spapi.SpApiTestFixture.ordersPageWithNext;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4 契约测试：用 WireMock 模拟 Amazon SP-API，锁住「我们理解的平台契约」。
 *
 * <p><b>为什么 contrat 测试比 Mock 更有价值</b>：Mockito 只能验证「我们调用了自己的方法」，
 * 而这里验证的是<b>真实 HTTP</b> —— 路径、查询参数、鉴权头、状态码到异常语义的映射。
 * 平台改一个字段名、加一条限流规则，这些用例会红；而纯 Mock 测试会全绿。
 *
 * <p><b>覆盖的分支</b>（对应 {@code OrderPullStrategy} 的两种合法失败写法）：
 * <ul>
 *   <li>成功 / 分页 / 空结果 / 明细缺失 → 正常与边界；</li>
 *   <li>429、5xx、读超时 → 可重试（{@link PlatformUnavailableException} 家族），
 *       交给 P1-2 的 Resilience4j；</li>
 *   <li>400、401 持久失败、LWA 400 → 不可重试（{@link SpApiClientException}），
 *       转业务失败结果。</li>
 * </ul>
 *
 * <p>容错链路的「重试 → 熔断 → 降级」端到端行为由 P1-2 的容器冒烟覆盖，
 * 本类不重复验证 AOP 代理，只锁住自己这层的异常分类（分类错了，上层配置再对也没用）。
 */
@DisplayName("P1-4 SP-API 契约：真实 HTTP 语义（WireMock 模拟平台）")
class SpApiOrderClientContractTest {

    private static final String MARKETPLACE = SpApiTestFixture.MARKETPLACE;

    @RegisterExtension
    static final WireMockExtension PLATFORM = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private SpApiProperties props;
    private LwaTokenClient tokens;
    private SpApiOrderClient client;
    private SpApiOrderMapper mapper;

    @BeforeEach
    void setUp() {
        // 静态共享服务器 + 每例重置：保证「请求计数」断言不受其他用例影响
        PLATFORM.resetAll();
        props = SpApiTestFixture.properties(PLATFORM);
        tokens = SpApiTestFixture.tokenClient(props);
        client = SpApiTestFixture.orderClient(props, tokens);
        mapper = SpApiTestFixture.mapper(props);
    }

    // ---------------------------------------------------------------- 正常路径

    @Test
    @DisplayName("成功：平台报文映射为统一 DTO，并带上鉴权头与必需查询参数")
    void mapsPlatformPayloadToUnifiedDto() {
        stubToken();
        props.setFetchItemTitles(true);
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .withQueryParam("MarketplaceIds", equalTo(MARKETPLACE))
                .withQueryParam("NextToken", absent())
                .willReturn(okJson(ordersPage(orderJson("AMZ-1001", "Shipped", "Bruchsal", "DE")))));
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders/AMZ-1001/orderItems"))
                .willReturn(okJson("{\"payload\":{\"OrderItems\":[{\"Title\":\"AeroSleep 婴儿床\"}]}}")));

        SpApiFetchResult result = client.fetchOrders();

        assertEquals(1, result.pages(), "无 NextToken 时只应请求一页");
        assertFalse(result.truncated());
        assertEquals(1, result.orders().size());

        OrderDto dto = mapper.toDtos(result.orders()).get(0);
        assertEquals("AMZ-1001", dto.orderId());
        assertEquals("AeroSleep 婴儿床", dto.product(), "商品名必须来自 /orderItems（列表接口不含）");
        assertEquals("Bruchsal", dto.warehouse(), "收货城市应映射到履约仓");
        assertEquals("Shipped", dto.status(), "OrderStatus 原样透传");
        assertTrue(dto.isNormal(), "地址完整时不应打异常标签");

        // 契约断言：鉴权头 + 三个必备查询参数（少一个平台就返回 400）
        PLATFORM.verify(getRequestedFor(urlPathEqualTo("/orders/v0/orders"))
                .withHeader(SpApiOrderClient.ACCESS_TOKEN_HEADER, equalTo("at-contract"))
                .withQueryParam("CreatedAfter", matching("\\d{4}-\\d{2}-\\d{2}T.*Z"))
                .withQueryParam("MaxResultsPerPage", equalTo("50")));
    }

    @Test
    @DisplayName("分页：按 NextToken 翻页并合并，令牌只换一次（缓存生效）")
    void followsPaginationAndCachesToken() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .withQueryParam("NextToken", absent())
                .willReturn(okJson(ordersPageWithNext("PAGE-2",
                        orderJson("AMZ-1", "Shipped", "Bruchsal", "DE"),
                        orderJson("AMZ-2", "Pending", "Moenchengladbach", "DE")))));
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .withQueryParam("NextToken", equalTo("PAGE-2"))
                .willReturn(okJson(ordersPage(orderJson("AMZ-3", "Unshipped", "Bruchsal", "DE")))));

        SpApiFetchResult result = client.fetchOrders();

        assertEquals(2, result.pages());
        assertEquals(List.of("AMZ-1", "AMZ-2", "AMZ-3"),
                result.orders().stream().map(SpApiOrder::orderId).toList(),
                "跨页结果必须按页序拼接，顺序错乱会让「最近订单优先」的报表失真");
        PLATFORM.verify(2, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
        PLATFORM.verify(getRequestedFor(urlPathEqualTo("/orders/v0/orders"))
                .withQueryParam("NextToken", equalTo("PAGE-2")));
        PLATFORM.verify(1, postRequestedFor(urlEqualTo("/auth/o2/token")));
    }

    @Test
    @DisplayName("边界：payload 缺 Orders 字段 → 0 条，但不算失败")
    void toleratesMissingOrdersField() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(okJson("{\"payload\":{}}")));

        SpApiFetchResult result = client.fetchOrders();

        assertTrue(result.orders().isEmpty(), "空列表是合法结果（这段区间真的没新单）");
        assertEquals(1, result.pages());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("护栏：NextToken 永不收敛时受 max-pages 限制，标记 truncated 而不是无限翻页")
    void stopsAtMaxPages() {
        props.setMaxPages(1);
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(okJson(ordersPageWithNext("ALWAYS-MORE",
                        orderJson("AMZ-1", "Shipped", "Bruchsal", "DE")))));

        SpApiFetchResult result = client.fetchOrders();

        assertTrue(result.truncated(), "必须显式标记「结果可能不完整」，而不是假装成功");
        assertEquals(1, result.pages());
        PLATFORM.verify(1, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
    }

    @Test
    @DisplayName("明细接口失败不拖垮主流程：商品名回退占位符，订单照常返回")
    void itemLookupFailureIsNonFatal() {
        stubToken();
        props.setFetchItemTitles(true);
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(okJson(ordersPage(orderJson("AMZ-1001", "Shipped", "Bruchsal", "DE")))));
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders/AMZ-1001/orderItems"))
                .willReturn(aResponse().withStatus(500)));

        SpApiFetchResult result = client.fetchOrders();

        assertEquals(1, result.orders().size(), "明细是锦上添花，不能让它把整批订单拉垮");
        assertEquals(SpApiOrderMapper.UNKNOWN_PRODUCT, mapper.toDto(result.orders().get(0)).product());
    }

    // ------------------------------------------------- 可重试：交给 Resilience4j

    @Test
    @DisplayName("429 限流：抛可重试异常并解析 Retry-After（子类命中父类的 retry-exceptions）")
    void rateLimitIsRetryable() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7")));

        PlatformUnavailableException thrown = assertThrows(RateLimitedException.class,
                () -> client.fetchOrders());

        assertEquals(7L, ((RateLimitedException) thrown).getRetryAfterSeconds(),
                "Retry-After 要能读到日志/探针里，否则无法回答「平台要我退避多久」");
        assertTrue(PlatformUnavailableException.class.isAssignableFrom(RateLimitedException.class),
                "429 必须落在 retry-exceptions 的父类上，否则 P1-2 配的重试不会生效");
    }

    @Test
    @DisplayName("503 服务端错误：抛可重试异常（不是业务失败）")
    void serverErrorIsRetryable() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(503)));

        PlatformUnavailableException thrown = assertThrows(PlatformUnavailableException.class,
                () -> client.fetchOrders());

        assertFalse(SpApiClientException.class.isInstance(thrown),
                "5xx 属于可重试家族，不能落进不可重试的业务失败类型");
        assertTrue(thrown.getMessage().contains("503"), "错误信息要保留平台状态码，便于运维定位");
    }

    @Test
    @DisplayName("读超时：平台僵死 → 抛可重试异常，且不超过配置的读超时")
    void readTimeoutIsRetryable() {
        stubToken();
        // 桩延迟 1.5s，远大于 300ms 的 read-timeout
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(okJson(ordersPage(orderJson("AMZ-1", "Shipped", "Bruchsal", "DE")))
                        .withFixedDelay(1500)));

        long startedAt = System.currentTimeMillis();
        PlatformUnavailableException thrown = assertThrows(PlatformUnavailableException.class,
                () -> client.fetchOrders());
        long elapsed = System.currentTimeMillis() - startedAt;

        assertTrue(thrown.getMessage().contains("超时"), "错误信息要说明是超时而不是平台拒绝");
        assertTrue(elapsed < 1400,
                "必须在读超时（300ms 量级）就放弃，而不是等到平台 1.5s 后才返回 —— 实测 " + elapsed + "ms");
    }

    // ------------------------------------------- 不可重试：转成业务失败（不浪费配额）

    @Test
    @DisplayName("400 参数/契约错：抛不可重试异常（重试只会打光配额）")
    void clientErrorIsNotRetryable() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"errors\":[{\"code\":\"InvalidInput\"}]}")));

        SpApiClientException thrown = assertThrows(SpApiClientException.class,
                () -> client.fetchOrders());

        assertFalse(PlatformUnavailableException.class.isInstance(thrown),
                "必须与可重试家族分开：否则 400 会被重试 3 次并计入熔断失败率");
        PLATFORM.verify(1, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
    }

    @Test
    @DisplayName("401 一次：主动刷新 LWA 令牌后成功（令牌被平台提前吊销）")
    void refreshesTokenOnceOnUnauthorized() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .inScenario("token-expired").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("refreshed"));
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .inScenario("token-expired").whenScenarioStateIs("refreshed")
                .willReturn(okJson(ordersPage(orderJson("AMZ-1001", "Shipped", "Bruchsal", "DE")))));

        SpApiFetchResult result = client.fetchOrders();

        assertEquals(1, result.orders().size(), "401 是可自愈的：换令牌后应拿到数据");
        // 两次：首次换取 + 401 后强制刷新（缓存被 invalidate）
        PLATFORM.verify(2, postRequestedFor(urlEqualTo("/auth/o2/token")));
        PLATFORM.verify(2, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
    }

    @Test
    @DisplayName("401 持续：只重试一次就转不可重试失败（refresh-token 已失效）")
    void persistentUnauthorizedBecomesNonRetryable() {
        stubToken();
        PLATFORM.stubFor(get(urlPathEqualTo("/orders/v0/orders"))
                .willReturn(aResponse().withStatus(401)));

        SpApiClientException thrown = assertThrows(SpApiClientException.class,
                () -> client.fetchOrders());

        assertTrue(thrown.getMessage().contains("重新授权"), "要给出可行动的结论：凭据需要重新授权");
        // 只允许重试一次：刷令牌成本低，但无限刷会把平台限流触发
        PLATFORM.verify(2, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
    }

    @Test
    @DisplayName("LWA 400（client-id 错）：不可重试，且不会再打订单接口")
    void lwaClientErrorStopsBeforeOrdersCall() {
        PLATFORM.stubFor(post(urlEqualTo("/auth/o2/token"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_client\"}")));

        SpApiClientException thrown = assertThrows(SpApiClientException.class,
                () -> client.fetchOrders());

        assertTrue(thrown.getMessage().contains("LWA"));
        // 换令牌就失败了，不应该再去打订单接口（那只会多一条 401 噪声日志）
        PLATFORM.verify(0, getRequestedFor(urlPathEqualTo("/orders/v0/orders")));
    }

    @Test
    @DisplayName("LWA 503：可重试（令牌端点故障也属于平台不可用）")
    void lwaServerErrorIsRetryable() {
        PLATFORM.stubFor(post(urlEqualTo("/auth/o2/token"))
                .willReturn(aResponse().withStatus(503)));

        PlatformUnavailableException thrown = assertThrows(PlatformUnavailableException.class,
                () -> client.fetchOrders());

        assertFalse(SpApiClientException.class.isInstance(thrown),
                "令牌端点 5xx 是可重试的平台不可用，不是配置错");
    }

    // ----------------------------------------------------------------- 映射规则

    @Test
    @DisplayName("映射：收货城市缺失 → ADDRESS_INVALID 标签 + 兜底仓")
    void missingCityBecomesAddressInvalid() {
        OrderDto dto = mapper.toDto(new SpApiOrder("AMZ-9", "Pending", null, "DE", null));

        assertEquals(SpApiOrderMapper.TAG_ADDRESS_INVALID, dto.errorTag());
        assertFalse(dto.isNormal(), "地址不合规的订单不能直接进履约流水线");
        assertEquals("Bruchsal", dto.warehouse(), "城市缺失时用 default-warehouse 兜底");
        assertNotNull(dto.product());
    }

    @Test
    @DisplayName("映射：城市 → 履约仓大小写无关（配置驱动，不写死在代码里）")
    void mapsCityToWarehouseIgnoringCase() {
        assertEquals("Mönchengladbach",
                mapper.toDto(new SpApiOrder("AMZ-9", "Shipped", "moenchengladbach", "DE", "X")).warehouse());
        assertEquals("Mönchengladbach",
                mapper.toDto(new SpApiOrder("AMZ-9", "Shipped", "Moenchengladbach", "DE", "X")).warehouse());
        assertEquals("Bruchsal",
                mapper.toDto(new SpApiOrder("AMZ-9", "Shipped", "Hamburg", "DE", "X")).warehouse(),
                "未配置的城市应兜底而不是留下空仓库（空仓库会让拣货任务无从下手）");
    }

    // ------------------------------------------------------------------ 辅助方法

    private void stubToken() {
        PLATFORM.stubFor(post(urlEqualTo("/auth/o2/token"))
                .willReturn(okJson(SpApiTestFixture.tokenJson())));
    }
}
