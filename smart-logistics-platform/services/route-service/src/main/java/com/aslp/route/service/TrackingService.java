package com.aslp.route.service;

import com.aslp.route.config.TrackingProperties;
import com.aslp.route.tracking.Carrier;
import com.aslp.route.tracking.MockTrackingClient;
import com.aslp.route.tracking.TrackingClient;
import com.aslp.route.tracking.TrackingClientException;
import com.aslp.route.tracking.TrackingFetcher;
import com.aslp.route.tracking.TrackingNotFoundException;
import com.aslp.route.tracking.TrackingUnavailableException;
import com.aslp.route.tracking.TrackingView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 尾程追踪服务（M3「一单到底」）—— 本项目的第三处真实外部契约（前两处：SP-API、MinIO）。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>承运商识别</b>：按单号长度/前缀做启发式判断；判不出来就要求显式传 {@code carrier}
 *       （见 {@link #detectCarrier}，**绝不替用户猜**）。</li>
 *   <li><b>查询与缓存</b>：承运商接口有配额、包裹状态变化慢（分钟级），
 *       同一单号在 {@code cache-ttl} 内复用结果；{@code refresh=true} 强制穿透。</li>
 *   <li><b>失败语义统一</b>：真实 DHL / 真实 DPD / Mock 三种实现的失败统一成
 *       「404 查无此单 / 503 依赖故障 / 502 上游拒绝」——调用方不必知道是哪一家。</li>
 * </ol>
 *
 * <h3>为什么缓存放在服务层而不是各客户端里</h3>
 * 缓存是**业务决策**（"这个状态多久算新鲜"），不是协议细节。放在服务层，两家承运商自动共享
 * 同一套 TTL 与淘汰策略；放进客户端就会变成两份各自演化的实现。
 *
 * <h3>可观测性（P1-1 / P1-3）</h3>
 * 一次查询产出 span + 指标 {@code aslp.tracking.lookup}：{@code carrier} / {@code cacheHit} / {@code state}
 * 是低基数标签（可聚合出"缓存命中率""哪家慢""多少包裹异常"）；**单号是高基数，只进 span**，
 * 绝不进指标 —— 否则时间序列会被单号打爆（与 P1-1b 里 traceId 不进 Loki 标签是同一条原则）。
 */
@Service
public class TrackingService {

    /** 观测名（Prometheus: aslp_tracking_lookup_seconds_*；Zipkin span: aslp.tracking.lookup）。 */
    public static final String OBSERVATION_LOOKUP = "aslp.tracking.lookup";

    /** DHL Paket：20 位数字（00340434…）。 */
    private static final Pattern DHL_PAKET = Pattern.compile("^\\d{20}$");

    /** DHL Express：10 位数字。 */
    private static final Pattern DHL_EXPRESS = Pattern.compile("^\\d{10}$");

    /** DHL eCommerce：JJD / JVGL / GM 前缀 + 字母数字。 */
    private static final Pattern DHL_ECOMMERCE =
            Pattern.compile("^(JJD|JVGL|GM)[0-9A-Z]{6,}$", Pattern.CASE_INSENSITIVE);

    /** DPD：14 位数字。 */
    private static final Pattern DPD_PARCEL = Pattern.compile("^\\d{14}$");

    private final TrackingProperties props;
    private final Map<Carrier, TrackingClient> clients;
    private final TrackingFetcher mockFetcher;
    private final ObservationRegistry observationRegistry;

    /**
     * 有界 LRU 缓存（{@code accessOrder=true} + {@code removeEldestEntry}）。
     *
     * <p>为什么不用裸 {@code ConcurrentHashMap}：在一个不断有新单号进来的系统里，
     * **无界缓存等于内存泄漏**。LRU + 上限是最小的正确实现
     * （换 Caffeine 也行，但那会为一个十几行的需求引入一个依赖）。
     *
     * <p>只缓存成功结果：失败（尤其"查无此单"）是会过期的事实 ——
     * 缓存它会让用户在包裹刚被揽收后仍看到"查无此单"，且持续整个 TTL。
     */
    private final Map<String, CachedTracking> cache;

    public TrackingService(TrackingProperties props,
                           List<TrackingClient> trackingClients,
                           MockTrackingClient mockTrackingClient,
                           MeterRegistry meterRegistry,
                           ObservationRegistry observationRegistry) {
        this.props = props;
        this.mockFetcher = mockTrackingClient;
        this.observationRegistry = observationRegistry;

        // 按 carrier() 自动建立映射：新增承运商只需实现 TrackingClient 并注册为 bean
        Map<Carrier, TrackingClient> resolved = new LinkedHashMap<>();
        for (TrackingClient client : trackingClients) {
            resolved.put(client.carrier(), client);
        }
        this.clients = Map.copyOf(resolved);

        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedTracking> eldest) {
                return size() > Math.max(1, TrackingService.this.props.getMaxCacheEntries());
            }
        });

        // 预热一个带标签的计数器：带标签的序列要在第一次使用后才出现，
        // 预热能让看板/告警在服务刚起来时就有序列可查（否则"没数据"与"没这个标签"分不清）。
        meterRegistry.counter("aslp.tracking.lookup.requests", "carrier", "DHL", "cacheHit", "false");
    }

    /**
     * 查询包裹轨迹。
     *
     * @param trackingNumber  单号（必填）
     * @param explicitCarrier 显式指定的承运商；null 时按单号启发式判断
     * @param refresh         true = 跳过缓存（客服刚打过电话等强实时场景）
     * @throws IllegalArgumentException     单号为空 / 无法识别承运商且未指定（→ 400）
     * @throws TrackingNotFoundException    查无此单（→ 404）
     * @throws TrackingUnavailableException 承运商不可用（→ 503）
     * @throws TrackingClientException      承运商拒绝请求（→ 502）
     */
    public TrackingView lookup(String trackingNumber, Carrier explicitCarrier, boolean refresh) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new IllegalArgumentException("单号不能为空");
        }
        String number = trackingNumber.trim();
        Carrier carrier = explicitCarrier != null ? explicitCarrier : detectCarrier(number);

        Observation observation = Observation.createNotStarted(OBSERVATION_LOOKUP, observationRegistry)
                .lowCardinalityKeyValue("carrier", carrier.name())
                .start();
        observation.highCardinalityKeyValue("trackingNumber", number);
        try (Observation.Scope ignored = observation.openScope()) {
            return doLookup(number, carrier, refresh, observation);
        } catch (RuntimeException e) {
            // 失败也要进 span：否则"追踪为什么失败/慢"在链路上完全看不到
            observation.error(e);
            throw e;
        }
    }

    private TrackingView doLookup(String number, Carrier carrier, boolean refresh, Observation observation) {
        String key = carrier + ":" + number;

        if (!refresh) {
            TrackingView cached = takeFromCache(key);
            if (cached != null) {
                observation.lowCardinalityKeyValue("cacheHit", "true");
                observation.lowCardinalityKeyValue("state", cached.state().name());
                return cached;
            }
        }
        observation.lowCardinalityKeyValue("cacheHit", "false");

        TrackingView view = currentFetcher(carrier).fetch(carrier, number)
                .withMaxEvents(props.getMaxEvents());
        observation.lowCardinalityKeyValue("state", view.state().name());

        putInCache(key, view);
        return view;
    }

    /** 当前生效的拉取实现：Mock 模式下两家都走 Mock（无凭据也能演示）。 */
    private TrackingFetcher currentFetcher(Carrier carrier) {
        if (props.isMockEnabled()) {
            return mockFetcher;
        }
        TrackingClient client = clients.get(carrier);
        if (client == null) {
            // 配置了真实模式却没有该承运商的实现：快速失败并说清原因，
            // 而不是回一个"查无此单"——那会让客服拿着正确单号在错误的承运商上反复核对
            throw new IllegalStateException(
                    "未配置 " + carrier + " 的追踪客户端（aslp.tracking.mock-enabled=false）");
        }
        return client;
    }

    private TrackingView takeFromCache(String key) {
        CachedTracking entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            // 惰性过期：读时顺手清掉（不需要后台清理任务，也不会让过期项继续占内存）
            cache.remove(key);
            return null;
        }
        // 命中缓存不重打上游；标记 stale 让调用方知道"这不是刚问来的"
        return entry.view().withStale(true);
    }

    private void putInCache(String key, TrackingView view) {
        Duration ttl = props.getCacheTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;   // TTL<=0 表示关闭缓存（排查"是不是缓存害的"时很方便）
        }
        cache.put(key, new CachedTracking(view, Instant.now().plus(ttl)));
    }

    /** 缓存条目（视图 + 过期时刻）。 */
    private record CachedTracking(TrackingView view, Instant expiresAt) {
    }

    /**
     * 单号 → 承运商 的启发式识别。
     *
     * <p><b>为什么是"启发式"而不是权威判定</b>：两家都用纯数字单号，长度是唯一可靠的区分特征
     * （DHL Paket 20 位 / DHL Express 10 位 / DPD 14 位），但这只是行业惯例，不是协议保证。
     * 因此对外 API 保留显式 {@code carrier} 参数作为兜底：
     * **判不出来就报错要求指定，绝不猜一家然后回"查无此单"** ——
     * 那会让客服拿着正确单号在错误的承运商上反复核对。
     */
    public static Carrier detectCarrier(String trackingNumber) {
        if (trackingNumber == null || trackingNumber.isBlank()) {
            throw new IllegalArgumentException("单号不能为空");
        }
        String number = trackingNumber.trim();
        if (DPD_PARCEL.matcher(number).matches()) {
            return Carrier.DPD;
        }
        if (DHL_PAKET.matcher(number).matches()
                || DHL_EXPRESS.matcher(number).matches()
                || DHL_ECOMMERCE.matcher(number).matches()) {
            return Carrier.DHL;
        }
        throw new IllegalArgumentException("无法根据单号识别承运商"
                + "（DHL: 10/20 位数字或 JJD/JVGL/GM 前缀；DPD: 14 位数字），"
                + "请显式指定 carrier 参数：" + number);
    }
}
