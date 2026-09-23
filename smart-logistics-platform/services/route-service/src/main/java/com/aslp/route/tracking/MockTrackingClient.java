package com.aslp.route.tracking;

import com.aslp.route.config.TrackingProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性 Mock 追踪客户端（{@code aslp.tracking.mock-enabled=true} 时启用）。
 *
 * <p>与 M1 的 {@code MockAmazonStrategy} 同一意图：**没有生产凭据时，链路依然可测、可演示**。
 * 它返回的是一条完整的德国境内尾程轨迹（Bruchsal 揽收 → 中转 → Mönchengladbach 派送），
 * 数值由单号哈希决定 —— 同一单号每次结果一致（可断言、可复现），不同单号不同（便于演示多条）。
 *
 * <p><b>它不是"假装成功"</b>：状态、时间、事件都是真的走完
 * 「承运商原始状态码 → 归一化 → 视图」的全过程，因此归一化逻辑在本地也能被端到端验证。
 */
@Component
public class MockTrackingClient implements TrackingFetcher {

    private final TrackingProperties props;

    public MockTrackingClient(TrackingProperties props) {
        this.props = props;
    }

    @Override
    public TrackingView fetch(Carrier carrier, String trackingNumber) {
        if (carrier == null) {
            throw new IllegalArgumentException("Mock 客户端需要明确的承运商");
        }
        // 用单号哈希决定"包裹走到哪一步"，让演示数据稳定且多样（0..4 → 5 个状态）
        int progress = Math.floorMod(trackingNumber.hashCode(), 5);
        Instant now = Instant.now();

        List<TrackingEvent> events = new ArrayList<>();
        events.add(new TrackingEvent(ShipmentState.CREATED, "pre-transit",
                "Shipment information received (elektronische Auftragsdaten)", "Bruchsal", now.minus(Duration.ofHours(30))));
        events.add(new TrackingEvent(ShipmentState.PICKED_UP, "transit",
                "The shipment has been picked up", "Bruchsal", now.minus(Duration.ofHours(26))));
        if (progress >= 2) {
            events.add(new TrackingEvent(ShipmentState.IN_TRANSIT, "transit",
                    "The shipment has been processed in the destination parcel center",
                    "Mönchengladbach", now.minus(Duration.ofHours(10))));
        }
        if (progress >= 3) {
            events.add(new TrackingEvent(ShipmentState.OUT_FOR_DELIVERY, "transit",
                    "The shipment is out for delivery", "Mönchengladbach", now.minus(Duration.ofHours(3))));
        }
        if (progress >= 4) {
            events.add(new TrackingEvent(ShipmentState.DELIVERED, "delivered",
                    "The shipment has been successfully delivered", "Mönchengladbach", now.minus(Duration.ofHours(1))));
        }

        List<TrackingEvent> sorted = DhlTrackingClient.sortNewestFirst(events);
        TrackingEvent latest = sorted.get(0);

        return new TrackingView(
                trackingNumber,
                carrier,
                latest.state(),
                latest.carrierStatus(),
                latest.description(),
                latest.location(),
                latest.occurredAt(),
                // 未妥投才给 ETA（妥投后再给"预计送达"是自相矛盾的）
                latest.state() == ShipmentState.DELIVERED ? null : now.plus(Duration.ofHours(6)),
                sorted,
                false);
    }
}
