package com.aslp.route.service;

import com.aslp.route.config.TrackingProperties;
import com.aslp.route.tracking.Carrier;
import com.aslp.route.tracking.MockTrackingClient;
import com.aslp.route.tracking.ShipmentState;
import com.aslp.route.tracking.TrackingClient;
import com.aslp.route.tracking.TrackingEvent;
import com.aslp.route.tracking.TrackingView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追踪服务单测（M3）：承运商识别 + 缓存语义 + 参数校验。
 *
 * <p>这里用<b>计数版假客户端</b>而不是 Mockito：本类要断言的核心是
 * 「上游被调了几次」——缓存命中时**必须为 0**。计数器比 verify() 更直观，
 * 也顺手避免了"Mockito 认为调用了一次、其实是代理层缓存了"这类误判。
 */
class TrackingServiceTest {

    /** 记录调用次数的假客户端。 */
    private static final class CountingClient implements TrackingClient {
        private final Carrier carrier;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingClient(Carrier carrier) {
            this.carrier = carrier;
        }

        @Override
        public Carrier carrier() {
            return carrier;
        }

        @Override
        public TrackingView fetch(Carrier requested, String trackingNumber) {
            calls.incrementAndGet();
            return new TrackingView(trackingNumber, carrier, ShipmentState.IN_TRANSIT, "transit",
                    "In transit", "Bruchsal", Instant.parse("2026-09-22T10:00:00Z"), null,
                    List.of(new TrackingEvent(ShipmentState.IN_TRANSIT, "transit", "In transit",
                            "Bruchsal", Instant.parse("2026-09-22T10:00:00Z"))), false);
        }
    }

    private TrackingProperties props;
    private CountingClient dhl;
    private CountingClient dpd;

    @BeforeEach
    void setUp() {
        props = new TrackingProperties();
        props.setMockEnabled(false);
        props.setCacheTtl(Duration.ofSeconds(60));
        dhl = new CountingClient(Carrier.DHL);
        dpd = new CountingClient(Carrier.DPD);
    }

    private TrackingService service() {
        return new TrackingService(props, List.of(dhl, dpd), new MockTrackingClient(props),
                new SimpleMeterRegistry(), ObservationRegistry.create());
    }

    // ─────────────── 承运商识别 ───────────────

    @Test
    @DisplayName("按单号识别承运商（长度是唯一可靠特征）")
    void detectsCarrierByTrackingNumberShape() {
        assertEquals(Carrier.DHL, TrackingService.detectCarrier("00340434161094000000"));  // 20 位
        assertEquals(Carrier.DHL, TrackingService.detectCarrier("1234567890"));            // 10 位 Express
        assertEquals(Carrier.DHL, TrackingService.detectCarrier("JJD0002212345678"));      // eCommerce
        assertEquals(Carrier.DPD, TrackingService.detectCarrier("01234567890123"));        // 14 位
    }

    @Test
    @DisplayName("识别不出来时要求显式指定，绝不替用户猜一家")
    void unknownShapeRequiresExplicitCarrier() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TrackingService.detectCarrier("ABC-123"));
        assertTrue(ex.getMessage().contains("carrier"));
        assertThrows(IllegalArgumentException.class, () -> TrackingService.detectCarrier("  "));
    }

    @Test
    void explicitCarrierWinsOverDetection() {
        // 显式指定必须生效：这是"识别不了/识别错"时的兜底通道
        TrackingView view = service().lookup("00340434161094000000", Carrier.DPD, false);
        assertEquals(Carrier.DPD, view.carrier());
        assertEquals(1, dpd.calls.get());
        assertEquals(0, dhl.calls.get());
    }

    // ─────────────── 缓存语义 ───────────────

    @Test
    @DisplayName("缓存命中不再打上游，且如实标记 stale=true")
    void cacheHitAvoidsUpstreamCallAndIsMarkedStale() {
        TrackingService service = service();

        TrackingView first = service.lookup("00340434161094000000", null, false);
        assertFalse(first.stale(), "首次查询是新鲜数据");
        assertEquals(1, dhl.calls.get());

        TrackingView second = service.lookup("00340434161094000000", null, false);
        assertTrue(second.stale(), "命中缓存必须标记 stale，不能对调用方说谎");
        assertEquals(1, dhl.calls.get(), "缓存命中时上游调用次数必须仍是 1");
    }

    @Test
    void refreshBypassesCache() {
        TrackingService service = service();
        service.lookup("00340434161094000000", null, false);
        TrackingView refreshed = service.lookup("00340434161094000000", null, true);

        assertFalse(refreshed.stale());
        assertEquals(2, dhl.calls.get(), "refresh=true 必须穿透缓存");
    }

    @Test
    void cacheIsKeyedByCarrierAndNumber() {
        TrackingService service = service();
        // 同一个单号、指定不同承运商：不能互相命中（否则会把 DHL 的结果当 DPD 的返回）
        service.lookup("00340434161094000000", Carrier.DHL, false);
        service.lookup("00340434161094000000", Carrier.DPD, false);

        assertEquals(1, dhl.calls.get());
        assertEquals(1, dpd.calls.get());
    }

    @Test
    void expiredEntryIsNotServed() throws InterruptedException {
        // TTL 是真实时间：要验证"过期"就必须让它真的过去一会儿。
        // 用 20ms/50ms 而不是 1ms：1ms 在微秒级时钟下会被两次调用挤在同一个毫秒里，测试变得随机。
        props.setCacheTtl(Duration.ofMillis(20));
        TrackingService service = service();

        service.lookup("00340434161094000000", null, false);
        Thread.sleep(50);
        TrackingView second = service.lookup("00340434161094000000", null, false);

        assertFalse(second.stale(), "过期后必须重新查上游");
        assertEquals(2, dhl.calls.get());
    }

    @Test
    @DisplayName("TTL<=0 关闭缓存（排查'是不是缓存害的'时的开关）")
    void zeroTtlDisablesCache() {
        props.setCacheTtl(Duration.ZERO);
        TrackingService service = service();

        service.lookup("00340434161094000000", null, false);
        service.lookup("00340434161094000000", null, false);

        assertEquals(2, dhl.calls.get());
    }

    @Test
    @DisplayName("缓存有上限（无界缓存就是内存泄漏）")
    void cacheIsBoundedByMaxEntries() {
        props.setMaxCacheEntries(2);
        TrackingService service = service();

        // 3 个不同单号 → 最早的被 LRU 淘汰
        service.lookup("00340434161094000001", null, false);
        service.lookup("00340434161094000002", null, false);
        service.lookup("00340434161094000003", null, false);
        int callsAfterFill = dhl.calls.get();
        assertEquals(3, callsAfterFill);

        // 再次查第一个：已被淘汰 → 需要重新打上游（证明上限真的在起作用）
        service.lookup("00340434161094000001", null, false);
        assertEquals(callsAfterFill + 1, dhl.calls.get());
    }

    // ─────────────── 其他 ───────────────

    @Test
    void blankTrackingNumberIsRejected() {
        TrackingService service = service();
        assertThrows(IllegalArgumentException.class, () -> service.lookup("  ", null, false));
        assertThrows(IllegalArgumentException.class, () -> service.lookup(null, null, false));
    }

    @Test
    @DisplayName("Mock 模式下不碰真实客户端（无凭据也能演示）")
    void mockModeDoesNotTouchRealClients() {
        props.setMockEnabled(true);
        TrackingService service = service();

        TrackingView view = service.lookup("00340434161094000000", null, false);

        assertEquals(Carrier.DHL, view.carrier());
        assertEquals(0, dhl.calls.get(), "Mock 模式下不应调用真实 DHL 客户端");
        assertTrue(view.events().size() >= 2, "Mock 也要给出完整轨迹（走完归一化全过程）");
    }

    @Test
    void eventsAreTruncatedToConfiguredMax() {
        props.setMockEnabled(true);
        props.setMaxEvents(2);
        TrackingService service = service();

        // 单号哈希决定 Mock 的进度（0..4 → 最多 5 个事件），上限 2 必须生效
        TrackingView view = service.lookup("00340434161094000000", null, false);
        assertTrue(view.events().size() <= 2, "事件数应被截断到 2，实际 " + view.events().size());
    }
}
