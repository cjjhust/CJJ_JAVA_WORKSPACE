package com.aslp.order.spapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code GET /orders/v0/orders/{orderId}/orderItems} 的响应报文。
 *
 * <p>订单列表接口不返回商品名，M1 的 {@code OrderDto.product} 只能靠这个接口补齐。
 * 这里只取第一条明细的 {@code Title}（跨境场景下「一个订单一件主打商品」是主流形态）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpApiOrderItemsPage(@JsonProperty("payload") Payload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(@JsonProperty("OrderItems") List<RawItem> orderItems) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawItem(@JsonProperty("Title") String title) {
    }
}
