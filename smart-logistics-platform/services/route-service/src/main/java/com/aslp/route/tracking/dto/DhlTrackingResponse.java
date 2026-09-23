package com.aslp.route.tracking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * DHL Unified Shipment Tracking 响应报文（{@code GET /track/shipments}）。
 *
 * <p>只声明我们真正用到的字段，其余靠 {@code ignoreUnknown = true} 忽略：
 * 承运商报文会不定期新增字段（甚至加整个子树），我们不该因此反序列化失败。
 * 这也意味着**报文字段改名会立刻在契约测试里暴露**（而不是静默变成 null）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DhlTrackingResponse(List<Shipment> shipments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Shipment(
            String id,
            String service,
            Status status,
            String estimatedTimeOfDelivery,
            List<Event> events) {
    }

    /** 当前状态。{@code statusCode} 是机器可读码，{@code status}/{@code description} 是文案。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(
            String timestamp,
            String statusCode,
            String status,
            String description,
            Location location) {
    }

    /** 历史事件。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(
            String timestamp,
            String statusCode,
            String status,
            String description,
            Location location) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(Address address) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String addressLocality, String countryCode) {
    }
}
