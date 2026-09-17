package com.aslp.order.spapi;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1-4 契约测试夹具：把「指向 WireMock 的客户端三件套 + 平台报文构造」集中一处，
 * 避免三个测试类各写一份装配逻辑（夹具漂移会让契约测试互相矛盾）。
 *
 * <p>测试里用的是**真实** {@code RestClient} + 真实 HTTP（WireMock 起在随机端口上），
 * 只有平台侧是假的 —— 这正是契约测试与 Mock 测试的分界线。
 */
public final class SpApiTestFixture {

    /** Amazon.de 站点 ID。 */
    public static final String MARKETPLACE = "A1PA6795UKMFR9";

    private SpApiTestFixture() {
    }

    /** 指向 WireMock 的完整配置（凭据齐备；超时收短便于测超时分支）。 */
    public static SpApiProperties properties(WireMockExtension platform) {
        SpApiProperties props = new SpApiProperties();
        props.setEndpoint(platform.baseUrl());
        props.setTokenUrl(platform.baseUrl() + "/auth/o2/token");
        props.setClientId("contract-client");
        props.setClientSecret("contract-secret");
        props.setRefreshToken("contract-refresh-token");
        props.setConnectTimeout(Duration.ofMillis(300));
        props.setReadTimeout(Duration.ofMillis(300));
        props.setFetchItemTitles(false);
        props.setWarehouseByCity(new LinkedHashMap<>(Map.of(
                "bruchsal", "Bruchsal",
                "moenchengladbach", "Mönchengladbach")));
        return props;
    }

    public static LwaTokenClient tokenClient(SpApiProperties props) {
        return new LwaTokenClient(props, RestClient.builder());
    }

    public static SpApiOrderClient orderClient(SpApiProperties props, LwaTokenClient tokens) {
        return new SpApiOrderClient(props, tokens, RestClient.builder());
    }

    public static SpApiOrderMapper mapper(SpApiProperties props) {
        return new SpApiOrderMapper(props);
    }

    /** 换令牌桩：固定返回 {@code at-contract}。 */
    public static String tokenJson() {
        return "{\"access_token\":\"at-contract\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
    }

    public static String ordersPage(String... orders) {
        return "{\"payload\":{\"Orders\":[" + String.join(",", orders) + "]}}";
    }

    public static String ordersPageWithNext(String nextToken, String... orders) {
        return "{\"payload\":{\"Orders\":[" + String.join(",", orders) + "],"
                + "\"NextToken\":\"" + nextToken + "\"}}";
    }

    /** 构造一条平台订单报文（字段名严格按 SP-API 契约的 PascalCase）。 */
    public static String orderJson(String orderId, String status, String city, String countryCode) {
        StringBuilder json = new StringBuilder("{\"AmazonOrderId\":\"").append(orderId)
                .append("\",\"OrderStatus\":\"").append(status).append("\"");
        if (city != null || countryCode != null) {
            json.append(",\"ShippingAddress\":{");
            if (city != null) {
                json.append("\"City\":\"").append(city).append("\"");
            }
            if (city != null && countryCode != null) {
                json.append(',');
            }
            if (countryCode != null) {
                json.append("\"CountryCode\":\"").append(countryCode).append("\"");
            }
            json.append('}');
        }
        return json.append('}').toString();
    }
}
