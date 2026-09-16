package com.aslp.order.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 订单 DTO 测试：{@code isNormal()} 是「异常打标」链路的判定入口，
 * 直接决定订单是否计入 {@code flagged} 统计。
 *
 * <p>边界：空白字符串（" "）必须视为正常，否则平台返回的空白标签会把订单误判为异常。
 */
class OrderDtoTest {

    @Test
    @DisplayName("errorTag 为 null：正常订单")
    void normalWhenErrorTagIsNull() {
        assertTrue(new OrderDto("AMZ-1001", "奶粉一件代发", "Bruchsal", "PAID", null).isNormal());
    }

    @Test
    @DisplayName("errorTag 为空白串：仍视为正常（防误判）")
    void normalWhenErrorTagIsBlank() {
        assertTrue(new OrderDto("AMZ-1001", "奶粉一件代发", "Bruchsal", "PAID", "   ").isNormal());
    }

    @Test
    @DisplayName("errorTag 有值：异常订单")
    void abnormalWhenErrorTagPresent() {
        OrderDto dto = new OrderDto("AMZ-1003", "逆向退货换标", "Bruchsal", "CREATED", "ADDRESS_INVALID");

        assertFalse(dto.isNormal());
    }

    @Test
    @DisplayName("四参便捷构造：等价于 errorTag=null（兼容旧签名）")
    void fourArgumentConstructorLeavesNoErrorTag() {
        OrderDto dto = new OrderDto("AMZ-1002", "大件中转", "Moenchengladbach", "PAID");

        assertTrue(dto.isNormal());
        assertTrue(dto.errorTag() == null);
    }
}
