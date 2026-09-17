package com.aslp.order.spapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code GET /orders/v0/orders} 的响应报文（只声明用得到的字段）。
 *
 * <p><b>契约要点</b>：
 * <ul>
 *   <li>平台报文是 PascalCase 且包在 {@code payload} 里，字段名必须显式声明 ——
 *       不能用「字段名相同即自动绑定」的侥幸，平台改一个大小写就是一个线上事故；</li>
 *   <li>{@code ignoreUnknown = true}：平台上新的字段不应该把老客户端打挂，
 *       这是长期演进的必要容忍度；</li>
 *   <li>{@code NextToken} 为 null/缺省 = 没有下一页（而不是空字符串），
 *       分页终止条件必须按 null 判断。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpApiOrdersPage(@JsonProperty("payload") Payload payload) {

    /** 空响应占位（平台返回 204/空体时的兜底，避免 NPE 在业务层炸开）。 */
    public static final SpApiOrdersPage EMPTY = new SpApiOrdersPage(new Payload(List.of(), null));

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            @JsonProperty("Orders") List<RawOrder> orders,
            @JsonProperty("NextToken") String nextToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawOrder(
            @JsonProperty("AmazonOrderId") String amazonOrderId,
            @JsonProperty("OrderStatus") String orderStatus,
            @JsonProperty("ShippingAddress") ShippingAddress shippingAddress) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShippingAddress(
            @JsonProperty("City") String city,
            @JsonProperty("CountryCode") String countryCode) {
    }
}
