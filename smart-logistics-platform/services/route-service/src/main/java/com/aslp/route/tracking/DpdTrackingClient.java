package com.aslp.route.tracking;

import com.aslp.route.config.TrackingProperties;
import com.aslp.route.tracking.dto.DpdTrackingResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * DPD 追踪客户端（真实 HTTP 实现）。
 *
 * <p>契约（我们据此写桩并做契约测试，见 {@code DpdTrackingResponse} 的诚实说明）：
 * <pre>
 * GET  {dpd-base-url}/tracking/v1/parcels/{parcelNumber}
 * header: API_KEY: {key}
 * 200  → { "status": "...", "statusDescription": "...", "parcelLifeCycle": [ {...} ] }
 * 404  → 查无此单
 * 429 / 5xx → 对方故障
 * </pre>
 *
 * <p><b>与 DHL 客户端的失败分类必须完全一致</b>：调用方（{@link TrackingService}）不关心是哪家，
 * 只关心"该重试还是该改请求"。两家客户端各写一套分类逻辑就容易漂移 ——
 * 因此这里刻意与 {@code DhlTrackingClient} 保持逐条对应的结构，并用同样的契约测试覆盖。
 */
@Component
public class DpdTrackingClient implements TrackingClient {

    /** 鉴权头：DPD Web API 用 API_KEY（不是 Authorization）。 */
    private static final String API_KEY_HEADER = "API_KEY";

    private final RestClient client;
    private final TrackingProperties props;

    public DpdTrackingClient(RestClient.Builder builder, TrackingProperties props) {
        this.props = props;
        this.client = TrackingHttp.build(builder, props.getDpdBaseUrl(), props);
    }

    @Override
    public Carrier carrier() {
        return Carrier.DPD;
    }

    @Override
    public TrackingView fetch(Carrier carrier, String trackingNumber) {
        if (carrier != Carrier.DPD) {
            throw new IllegalArgumentException("DpdTrackingClient 只支持 DPD，实际请求：" + carrier);
        }
        DpdTrackingResponse response;
        try {
            response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/tracking/v1/parcels/{parcelNumber}")
                            .build(trackingNumber))
                    .header(API_KEY_HEADER, props.getDpdApiKey())
                    .retrieve()
                    .body(DpdTrackingResponse.class);
        } catch (RestClientResponseException e) {
            throw translate(carrier, trackingNumber, e);
        } catch (ResourceAccessException e) {
            throw new TrackingUnavailableException(carrier,
                    "DPD 接口不可达：" + e.getMessage(), e, false, null);
        }

        if (response == null) {
            throw new TrackingNotFoundException(carrier, trackingNumber);
        }
        return toView(trackingNumber, response);
    }

    private RuntimeException translate(Carrier carrier, String trackingNumber, RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String body = e.getResponseBodyAsString();
        if (status.value() == 404) {
            return new TrackingNotFoundException(carrier, trackingNumber);
        }
        if (status.value() == 429) {
            Integer retryAfter = parseRetryAfter(e);
            return new TrackingUnavailableException(carrier,
                    "DPD 限流（429），建议 " + (retryAfter == null ? "稍后" : retryAfter + " 秒后") + " 重试",
                    e, true, retryAfter);
        }
        if (status.is5xxServerError()) {
            return new TrackingUnavailableException(carrier,
                    "DPD 服务端错误（HTTP " + status.value() + "）", e, false, null);
        }
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
            return null;
        }
    }

    private TrackingView toView(String trackingNumber, DpdTrackingResponse response) {
        List<TrackingEvent> events = new ArrayList<>();
        if (response.parcelLifeCycle() != null) {
            for (DpdTrackingResponse.LifeCycleEntry entry : response.parcelLifeCycle()) {
                events.add(new TrackingEvent(
                        TrackingStatusMapper.fromDpd(entry.status(), entry.label()),
                        entry.status(),
                        entry.label(),
                        entry.location(),
                        TrackingTimes.parse(entry.dateTime())));
            }
        }

        // DPD 的"当前状态"优先取顶层 status；顶层缺失时回退到最新一条事件
        // （某些响应只给轨迹不给顶层状态 —— 只认顶层会让响应变成"未知"）
        String statusCode = response.status();
        String statusDescription = response.statusDescription();
        if ((statusCode == null || statusCode.isBlank()) && !events.isEmpty()) {
            statusCode = events.get(0).carrierStatus();
            statusDescription = events.get(0).description();
        }

        List<TrackingEvent> sorted = DhlTrackingClient.sortNewestFirst(events);

        return new TrackingView(
                trackingNumber,
                Carrier.DPD,
                TrackingStatusMapper.fromDpd(statusCode, statusDescription),
                statusCode,
                statusDescription,
                sorted.isEmpty() ? null : sorted.get(0).location(),
                sorted.isEmpty() ? null : sorted.get(0).occurredAt(),
                TrackingTimes.parse(response.estimatedDelivery()),
                sorted,
                false);
    }
}
