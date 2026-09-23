package com.aslp.route.tracking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 承运商状态归一化测试（M3）。
 *
 * <p>这类映射代码的价值全在**边界**上：两家承运商大小写不统一、用词不一致、
 * 异常文案里还常含 "deliver" 字样。所以这里重点覆盖：
 * <ul>
 *   <li>异常 > 终态 的判定顺序（否则"投递失败"会被显示成"已送达"，这是最坏的错）；</li>
 *   <li>大小写/分隔符差异（DHL 用 {@code pre-transit}，DPD 用 {@code IN_TRANSIT}）；</li>
 *   <li><b>没见过的状态码必须落到 UNKNOWN</b>，绝不猜。</li>
 * </ul>
 */
class TrackingStatusMapperTest {

    @Test
    void dhlStatusCodesAreNormalized() {
        assertEquals(ShipmentState.CREATED, TrackingStatusMapper.fromDhl("pre-transit", "Shipment information received"));
        assertEquals(ShipmentState.IN_TRANSIT, TrackingStatusMapper.fromDhl("transit", "In transit"));
        assertEquals(ShipmentState.DELIVERED, TrackingStatusMapper.fromDhl("delivered", "Delivered"));
        assertEquals(ShipmentState.EXCEPTION, TrackingStatusMapper.fromDhl("failure", "Delivery attempt failed"));
    }

    @Test
    void dpdStatusCodesAreNormalizedCaseInsensitively() {
        assertEquals(ShipmentState.PICKED_UP, TrackingStatusMapper.fromDpd("PICKUP", "Parcel picked up from sender"));
        assertEquals(ShipmentState.IN_TRANSIT, TrackingStatusMapper.fromDpd("IN_TRANSIT", "Parcel processed at depot"));
        assertEquals(ShipmentState.IN_TRANSIT, TrackingStatusMapper.fromDpd("inTransit", "In transit to destination depot"));
        assertEquals(ShipmentState.OUT_FOR_DELIVERY, TrackingStatusMapper.fromDpd("OUT_FOR_DELIVERY", "With delivery courier"));
        assertEquals(ShipmentState.DELIVERED, TrackingStatusMapper.fromDpd("DELIVERED", "Delivered to neighbour"));
    }

    @Test
    @DisplayName("异常必须优先于妥投判定（'Delivery attempt failed' 里含 deliver）")
    void exceptionWinsOverDelivered() {
        // 两者文案都含 "deliver"：顺序写反会把失败当成功
        assertEquals(ShipmentState.EXCEPTION,
                TrackingStatusMapper.fromDhl("failure", "Delivery attempt failed"));
        assertEquals(ShipmentState.EXCEPTION,
                TrackingStatusMapper.normalize("Delivered to neighbour failed - returned to sender"));
    }

    @Test
    void germanLocalizedDescriptionsAreRecognized() {
        // 德国境内接口会回德语原文（本项目的实际业务场景）
        assertEquals(ShipmentState.DELIVERED, TrackingStatusMapper.normalize("Die Sendung wurde zugestellt"));
        assertEquals(ShipmentState.CREATED, TrackingStatusMapper.normalize("Auftragsdaten übermittelt"));
        assertEquals(ShipmentState.PICKED_UP, TrackingStatusMapper.normalize("Sendung wurde abgeholt"));
    }

    @Test
    @DisplayName("没见过的状态码 → UNKNOWN（不猜）")
    void unknownStatusStaysUnknown() {
        assertEquals(ShipmentState.UNKNOWN, TrackingStatusMapper.fromDhl("brand-new-code", "Some wording we never saw"));
        assertEquals(ShipmentState.UNKNOWN, TrackingStatusMapper.fromDpd(null, null));
        assertEquals(ShipmentState.UNKNOWN, TrackingStatusMapper.normalize("   "));
        assertEquals(ShipmentState.UNKNOWN, TrackingStatusMapper.normalize(null));
    }

    @Test
    void codeAndTextAreBothConsidered() {
        // 码不认识但文案认识 → 仍能归一化（承运商常靠文案表达细分状态）
        assertEquals(ShipmentState.IN_TRANSIT,
                TrackingStatusMapper.fromDhl("unknown", "The shipment has been processed in the parcel center"));
    }
}
