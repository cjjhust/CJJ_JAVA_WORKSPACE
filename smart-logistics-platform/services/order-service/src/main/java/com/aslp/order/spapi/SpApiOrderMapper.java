package com.aslp.order.spapi;

import com.aslp.order.strategy.OrderDto;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * SP-API 订单 → M1 统一 DTO 的映射（P1-4）。
 *
 * <p>这层回答两个业务问题：
 * <ol>
 *   <li><b>履约仓怎么定</b>：SP-API 不返回「发哪个仓」，只有收货城市。
 *       因此按 {@code aslp.order.amazon.warehouse-by-city} 做城市 → 仓库映射，
 *       未命中则落到 {@code default-warehouse}。<b>把口径放在配置里而不是代码里</b>，
 *       业务开新仓时不必改代码；</li>
 *   <li><b>异常怎么打标</b>：收货城市/国家缺失 → {@code ADDRESS_INVALID}，
 *       与 M1「异常物流单号自动打标」的既有标签口径一致，
 *       后续由客服后台（{@code POST /api/orders/{orderId}/correct}）修正。</li>
 * </ol>
 */
public final class SpApiOrderMapper {

    /** 明细接口失败时的商品名占位符（宁可显示「未知」也不要空字符串：报表按空值统计会失真）。 */
    static final String UNKNOWN_PRODUCT = "未知商品（明细接口未返回）";

    /** M1 既有异常标签：地址不合规。 */
    static final String TAG_ADDRESS_INVALID = "ADDRESS_INVALID";

    private final SpApiProperties props;

    public SpApiOrderMapper(SpApiProperties props) {
        this.props = props;
    }

    public List<OrderDto> toDtos(List<SpApiOrder> orders) {
        return orders.stream().map(this::toDto).toList();
    }

    public OrderDto toDto(SpApiOrder order) {
        return new OrderDto(
                order.orderId(),
                StringUtils.hasText(order.title()) ? order.title() : UNKNOWN_PRODUCT,
                resolveWarehouse(order.city()),
                order.status(),
                order.addressIncomplete() ? TAG_ADDRESS_INVALID : null);
    }

    /** 城市 → 履约仓（大小写无关；未配置则用默认仓）。 */
    String resolveWarehouse(String city) {
        if (!StringUtils.hasText(city)) {
            return props.getDefaultWarehouse();
        }
        String key = city.trim().toLowerCase(Locale.ROOT);
        return props.getWarehouseByCity().getOrDefault(key, props.getDefaultWarehouse());
    }
}
