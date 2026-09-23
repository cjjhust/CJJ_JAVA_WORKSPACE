package com.aslp.route.tracking;

import com.aslp.route.config.TrackingProperties;
import com.aslp.route.tracking.dto.DhlTrackingResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DHL Unified Shipment Tracking 客户端（真实 HTTP 实现）。
 *
 * <p>契约（我们据此写桩并做契约测试）：
 * <pre>
 * GET  {dhl-base-url}/track/shipments?trackingNumber={n}
 * header: DHL-API-Key: {key}
 * 200  → { "shipments": [ { "status": {...}, "events": [...], "estimatedTimeOfDelivery": "..." } ] }
 * 404  → 查无此单（DHL 用 404 表达"单号不存在"，不是错误）
 * 429  → 限流（带 Retry-After）
 * 5xx  → 对方故障
 * </pre>
 *
 * <p><b>200 但 shipments 为空也要当"查无此单"</b>：不同的网关/版本对同一事实的表达不一致，
 * 只认 404 会让一类查无此单被显示成"状态未知"，那对用户是最差的答案。
 */
@Component
public class DhlTrackingClient implements TrackingClient {

    /** 鉴权头：DHL Unified Tracking 用 API Key 而不是 Bearer（Bearer 是 OAuth 那套）。 */
    private static final String API_KEY_HEADER = "DHL-API-Key";

    private final RestClient client;
    private final TrackingProperties props;

    public DhlTrackingClient(RestClient.Builder builder, TrackingProperties props) {
        this.props = props;
        this.client = TrackingHttp.build(builder, props.getDhlBaseUrl(), props);
    }

    @Override
    public Carrier carrier() {
        return Carrier.DHL;
    }

    @Override
    public TrackingView fetch(Carrier carrier, String trackingNumber) {
        if (carrier != Carrier.DHL) {
            // 装配错误的快速失败：否则会被静默当成"查不到"而极难排查
            throw new IllegalArgumentException("DhlTrackingClient 只支持 DHL，实际请求：" + carrier);
        }
        DhlTrackingResponse response;
        try {
            response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/track/shipments")
                            .queryParam("trackingNumber", trackingNumber)
                            .build())
                    .header(API_KEY_HEADER, props.getDhlApiKey())
                    .retrieve()
                    .body(DhlTrackingResponse.class);
        } catch (RestClientResponseException e) {
            throw translate(carrier, trackingNumber, e);
        } catch (ResourceAccessException e) {
            // 连接超时 / 读取超时 / DNS 失败：对方或网络的问题
            throw new TrackingUnavailableException(carrier,
                    "DHL 接口不可达：" + e.getMessage(), e, false, null);
        }

        if (response == null || response.shipments() == null || response.shipments().isEmpty()) {
            throw new TrackingNotFoundException(carrier, trackingNumber);
        }
        return toView(trackingNumber, response.shipments().get(0));
    }

    /** 状态码 → 异常类型（这一层决定调用方"该重试还是该改请求"）。 */
    private RuntimeException translate(Carrier carrier, String trackingNumber, RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String body = e.getResponseBodyAsString();
        if (status.value() == 404) {
            return new TrackingNotFoundException(carrier, trackingNumber);
        }
        if (status.value() == 429) {
            Integer retryAfter = parseRetryAfter(e);
            return new TrackingUnavailableException(carrier,
                    "DHL 限流（429），建议 " + (retryAfter == null ? "稍后" : retryAfter + " 秒后") + " 重试",
                    e, true, retryAfter);
        }
        if (status.is5xxServerError()) {
            return new TrackingUnavailableException(carrier,
                    "DHL 服务端错误（HTTP " + status.value() + "）", e, false, null);
        }
        // 其余 4xx：可能是单号格式问题，也可能是我们请求/凭据的问题 → 分开成一类（502）
        return new TrackingClientException(carrier, status.value(), body);
    }

    private Integer parseRetryAfter(RestClientResponseException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(header.trim());
        } catch (NumberFormatException ignored) {
            // HTTP-date 形式（RFC 7231 允许）不解析：拿不到秒数就不猜
            return null;
        }
    }

    private TrackingView toView(String trackingNumber, DhlTrackingResponse.Shipment shipment) {
        DhlTrackingResponse.Status status = shipment.status();
        String statusCode = status == null ? null : status.statusCode();
        String statusText = status == null ? null : status.status();

        List<TrackingEvent> events = new ArrayList<>();
        if (shipment.events() != null) {
            for (DhlTrackingResponse.Event event : shipment.events()) {
                events.add(new TrackingEvent(
                        TrackingStatusMapper.fromDhl(event.statusCode(), firstNonNull(event.status(), event.description())),
                        firstNonNull(event.statusCode(), event.status()),
                        firstNonNull(event.description(), event.status()),
                        locality(event.location()),
                        TrackingTimes.parse(event.timestamp())));
            }
        }

        return new TrackingView(
                trackingNumber,
                Carrier.DHL,
                TrackingStatusMapper.fromDhl(statusCode, firstNonNull(statusText, status == null ? null : status.description())),
                firstNonNull(statusCode, statusText),
                firstNonNull(status == null ? null : status.description(), statusText),
                locality(status == null ? null : status.location()),
                TrackingTimes.parse(status == null ? null : status.timestamp()),
                TrackingTimes.parse(shipment.estimatedTimeOfDelivery()),
                sortNewestFirst(events),
                false);
    }

    /** 事件倒序（最新在前）：面客界面首先回答"现在到哪了"。 */
    static List<TrackingEvent> sortNewestFirst(List<TrackingEvent> events) {
        List<TrackingEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(TrackingEvent::occurredAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return List.copyOf(sorted);
    }

    private static String locality(DhlTrackingResponse.Location location) {
        if (location == null || location.address() == null) {
            return null;
        }
        DhlTrackingResponse.Address address = location.address();
        if (address.addressLocality() != null && address.countryCode() != null) {
            return address.addressLocality() + ", " + address.countryCode();
        }
        return firstNonNull(address.addressLocality(), address.countryCode());
    }

    private static String firstNonNull(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
