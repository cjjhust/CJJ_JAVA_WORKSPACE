package com.aslp.route.tracking;

import com.aslp.route.config.TrackingProperties;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.havingExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * DHL / DPD 追踪客户端契约测试（P1-9）。
 *
 * <p><b>为什么是契约测试而不是 Mock 测试</b>：这里要验证的恰恰是"接线"本身 ——
 * 请求路径与查询参数对不对、鉴权头有没有带、承运商报文能不能反序列化、
 * 状态码到异常类型的映射准不准、超时有没有真的生效。
 * 用 Mockito 打桩 {@code RestClient} 这些一条都验不到（那只是把我以为的答案再说一遍）。
 *
 * <p>客户端是<b>真的</b>，承运商是<b>假的</b>（WireMock 起在随机端口）—— 这就是分界线。
 */
class TrackingClientContractTest {

    @RegisterExtension
    static WireMockExtension carrier = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private TrackingProperties props;

    @BeforeEach
    void setUp() {
        props = new TrackingProperties();
        props.setMockEnabled(false);
        props.setDhlBaseUrl(carrier.baseUrl());
        props.setDpdBaseUrl(carrier.baseUrl());
        props.setDhlApiKey("contract-dhl-key");
        props.setDpdApiKey("contract-dpd-key");
        props.setConnectTimeout(Duration.ofMillis(500));
        props.setReadTimeout(Duration.ofMillis(500));   // 收短便于快速验证超时分支
    }

    private DhlTrackingClient dhl() {
        return new DhlTrackingClient(RestClient.builder(), props);
    }

    private DpdTrackingClient dpd() {
        return new DpdTrackingClient(RestClient.builder(), props);
    }

    private static final String NUMBER = "00340434161094000000";

    // ───────────────────────── DHL ─────────────────────────

    @Test
    void dhlHappyPathIsMappedAndSortedNewestFirst() {
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .withQueryParam("trackingNumber", equalTo(NUMBER))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(dhlShipment("delivered", "Delivered"))));

        TrackingView view = dhl().fetch(Carrier.DHL, NUMBER);

        assertEquals(Carrier.DHL, view.carrier());
        assertEquals(ShipmentState.DELIVERED, view.state());
        assertEquals("delivered", view.carrierStatus());
        assertEquals("Mönchengladbach, DE", view.lastLocation());
        assertNotNull(view.lastEventAt());
        assertFalse(view.stale(), "客户端产出的视图默认不是缓存命中");

        // 事件按时间倒序（最新在前）：面客界面首先回答"现在到哪了"
        assertTrue(view.events().size() >= 3);
        Instant first = view.events().get(0).occurredAt();
        Instant last = view.events().get(view.events().size() - 1).occurredAt();
        assertTrue(first.isAfter(last), "事件必须最新在前");
    }

    @Test
    void dhlSendsApiKeyHeader() {
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(dhlShipment("transit", "In transit"))));

        dhl().fetch(Carrier.DHL, NUMBER);

        // 鉴权头是最容易漏的契约细节：漏了生产环境会 401，而 WireMock 不加断言就不会报错
        carrier.verify(getRequestedFor(urlPathEqualTo("/track/shipments"))
                .withHeader("DHL-API-Key", equalTo("contract-dhl-key")));
    }

    @Test
    void dhlEmptyShipmentsMeansNotFoundEvenWith200() {
        // 不同网关/版本对"查无此单"的表达不一致：只认 404 会把这类情况显示成"状态未知"
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"shipments\":[]}")));

        TrackingNotFoundException ex =
                assertThrows(TrackingNotFoundException.class, () -> dhl().fetch(Carrier.DHL, NUMBER));
        assertEquals(Carrier.DHL, ex.getCarrier());
    }

    @Test
    void dhl404MeansNotFound() {
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"title\":\"No shipment with given tracking number found.\"}")));

        assertThrows(TrackingNotFoundException.class, () -> dhl().fetch(Carrier.DHL, NUMBER));
    }

    @Test
    @DisplayName("429 → 可重试异常 + 解析 Retry-After")
    void dhl429IsRateLimitedWithRetryAfter() {
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Retry-After", "7")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"title\":\"Too Many Requests\"}")));

        TrackingUnavailableException ex =
                assertThrows(TrackingUnavailableException.class, () -> dhl().fetch(Carrier.DHL, NUMBER));
        assertTrue(ex.isRateLimited());
        assertEquals(7, ex.getRetryAfterSeconds());
    }

    @Test
    void dhl503IsUnavailableAndNotRateLimited() {
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .willReturn(aResponse().withStatus(503)));

        TrackingUnavailableException ex =
                assertThrows(TrackingUnavailableException.class, () -> dhl().fetch(Carrier.DHL, NUMBER));
        assertFalse(ex.isRateLimited());
        assertEquals(Carrier.DHL, ex.getCarrier());
    }

    @Test
    @DisplayName("读超时真的生效（桩延迟 3s > 客户端 read-timeout 0.5s）")
    void dhlReadTimeoutIsEnforcedByClientNotByCarrier() {
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(3000)
                        .withHeader("Content-Type", "application/json")
                        .withBody(dhlShipment("delivered", "Delivered"))));

        long start = System.currentTimeMillis();
        TrackingUnavailableException ex =
                assertThrows(TrackingUnavailableException.class, () -> dhl().fetch(Carrier.DHL, NUMBER));
        long elapsed = System.currentTimeMillis() - start;

        // 断言的是"没有等满桩的 3 秒"：证明是我们主动超时，而不是对方返回
        assertTrue(elapsed < 2500, "应在读超时（0.5s）后失败，实际耗时 " + elapsed + "ms");
        assertTrue(ex.getMessage().contains("不可达"), "错误信息应指明是连接/超时问题：" + ex.getMessage());
    }

    @Test
    void dhl400IsClientErrorNotUnavailable() {
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"title\":\"Bad Request\"}")));

        TrackingClientException ex =
                assertThrows(TrackingClientException.class, () -> dhl().fetch(Carrier.DHL, NUMBER));
        assertEquals(400, ex.getUpstreamStatus());
    }

    @Test
    void adapterRefusesWrongCarrier() {
        // 装配错误的快速失败：若是静默处理，会被当成"查不到"而极难排查
        assertThrows(IllegalArgumentException.class, () -> dhl().fetch(Carrier.DPD, NUMBER));
        assertThrows(IllegalArgumentException.class, () -> dpd().fetch(Carrier.DHL, NUMBER));
    }

    // ───────────────────────── DPD ─────────────────────────

    @Test
    void dpdHappyPathIsMapped() {
        carrier.stubFor(get(urlPathEqualTo("/tracking/v1/parcels/01234567890123"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "parcelNumber": "01234567890123",
                                  "status": "IN_TRANSIT",
                                  "statusDescription": "In transit to destination depot",
                                  "parcelLifeCycle": [
                                    {"status":"PICKUP","label":"Parcel picked up from sender",
                                     "dateTime":"2026-09-21T09:30:00","location":"Bruchsal"},
                                    {"status":"IN_TRANSIT","label":"In transit to destination depot",
                                     "dateTime":"2026-09-22T11:05:00","location":"Mönchengladbach"}
                                  ]
                                }
                                """)));

        TrackingView view = dpd().fetch(Carrier.DPD, "01234567890123");

        assertEquals(Carrier.DPD, view.carrier());
        assertEquals(ShipmentState.IN_TRANSIT, view.state());
        assertEquals("Mönchengladbach", view.lastLocation());
        assertEquals(2, view.events().size());
        // DPD 的鉴权头是 API_KEY（不是 Authorization）—— 同样是最容易漏的契约细节
        carrier.verify(getRequestedFor(urlPathEqualTo("/tracking/v1/parcels/01234567890123"))
                .withHeader("API_KEY", equalTo("contract-dpd-key")));
    }

    @Test
    @DisplayName("顶层 status 缺失时回退到最新事件（否则响应会变成'状态未知'）")
    void dpdFallsBackToLatestEventWhenTopLevelStatusMissing() {
        carrier.stubFor(get(urlPathEqualTo("/tracking/v1/parcels/01234567890123"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"parcelNumber":"01234567890123",
                                 "parcelLifeCycle":[{"status":"OUT_FOR_DELIVERY","label":"With delivery courier",
                                                     "dateTime":"2026-09-22T08:00:00","location":"Mönchengladbach"}]}
                                """)));

        TrackingView view = dpd().fetch(Carrier.DPD, "01234567890123");

        assertEquals(ShipmentState.OUT_FOR_DELIVERY, view.state());
        assertEquals("OUT_FOR_DELIVERY", view.carrierStatus());
    }

    @Test
    void dpd404MeansNotFound() {
        carrier.stubFor(get(urlPathEqualTo("/tracking/v1/parcels/01234567890999"))
                .willReturn(aResponse().withStatus(404)));

        TrackingNotFoundException ex = assertThrows(TrackingNotFoundException.class,
                () -> dpd().fetch(Carrier.DPD, "01234567890999"));
        assertEquals(Carrier.DPD, ex.getCarrier());
    }

    @Test
    void dpd5xxIsUnavailable() {
        carrier.stubFor(get(urlPathEqualTo("/tracking/v1/parcels/01234567890123"))
                .willReturn(aResponse().withStatus(502)));

        assertThrows(TrackingUnavailableException.class,
                () -> dpd().fetch(Carrier.DPD, "01234567890123"));
    }

    @Test
    @DisplayName("未知字段不影响反序列化（承运商会加字段，我们不该因此挂掉）")
    void unknownCarrierFieldsAreIgnored() {
        carrier.stubFor(get(urlPathEqualTo("/tracking/v1/parcels/01234567890123"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"parcelNumber":"01234567890123","status":"DELIVERED",
                                 "brandNewTopLevelField":{"nested":[1,2,3]},
                                 "parcelLifeCycle":[{"status":"DELIVERED","label":"Delivered",
                                                     "dateTime":"2026-09-22T10:00:00","location":"Mönchengladbach",
                                                     "newScanField":"whatever"}]}
                                """)));

        assertEquals(ShipmentState.DELIVERED, dpd().fetch(Carrier.DPD, "01234567890123").state());
    }

    @Test
    void dhlBodyIsSentForEveryRequest() {
        // 显式给出桩（上面的用例各自的 stubFor 会累积到同一个服务器实例上）
        carrier.stubFor(get(urlPathEqualTo("/track/shipments"))
                .withQueryParam("trackingNumber", equalTo("00340434161094000009"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(dhlShipment("transit", "In transit"))));

        dhl().fetch(Carrier.DHL, "00340434161094000009");

        carrier.verify(getRequestedFor(urlPathEqualTo("/track/shipments"))
                .withQueryParam("trackingNumber", havingExactly("00340434161094000009")));
    }

    /** 最小可用的 DHL 报文（状态码/文案可参数化）。 */
    private static String dhlShipment(String statusCode, String statusText) {
        return """
                {
                  "shipments": [
                    {
                      "id": "00340434161094000000",
                      "service": "parcel-de",
                      "estimatedTimeOfDelivery": "2026-09-22T18:00:00",
                      "status": {
                        "timestamp": "2026-09-22T14:20:00",
                        "location": {"address": {"addressLocality": "Mönchengladbach", "countryCode": "DE"}},
                        "statusCode": "%s",
                        "status": "%s",
                        "description": "The shipment has been successfully delivered"
                      },
                      "events": [
                        {"timestamp":"2026-09-21T08:05:00",
                         "location":{"address":{"addressLocality":"Bruchsal","countryCode":"DE"}},
                         "statusCode":"pre-transit","status":"Shipment information received",
                         "description":"The shipment has been posted by the sender"},
                        {"timestamp":"2026-09-22T06:15:00",
                         "location":{"address":{"addressLocality":"Bruchsal","countryCode":"DE"}},
                         "statusCode":"transit","status":"In transit",
                         "description":"The shipment is out for delivery"},
                        {"timestamp":"2026-09-22T14:20:00",
                         "location":{"address":{"addressLocality":"Mönchengladbach","countryCode":"DE"}},
                         "statusCode":"%s","status":"%s",
                         "description":"The shipment has been successfully delivered"}
                      ]
                    }
                  ]
                }
                """.formatted(statusCode, statusText, statusCode, statusText);
    }
}
