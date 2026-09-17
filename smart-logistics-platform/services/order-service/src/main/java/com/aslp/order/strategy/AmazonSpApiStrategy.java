package com.aslp.order.strategy;

import com.aslp.order.spapi.SpApiClientException;
import com.aslp.order.spapi.SpApiFetchResult;
import com.aslp.order.spapi.SpApiOrderClient;
import com.aslp.order.spapi.SpApiOrderMapper;
import com.aslp.order.spapi.SpApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Amazon SP-API 策略实现（P1-4 起从骨架升级为真实 HTTP 实现）。
 *
 * <p>启用方式：{@code aslp.order.mock-enabled=false}，凭据由
 * {@code aslp.order.amazon.client-id / client-secret / refresh-token} 提供。
 *
 * <p><b>本类只做一件事：把「失败」分成两类</b>（{@link OrderPullStrategy} 契约允许
 * 的两种写法，本实现两种都用）：
 * <ul>
 *   <li><b>可重试的平台不可用</b>（429 限流 / 5xx / 超时）→
 *       <b>原样向上抛</b> {@link PlatformUnavailableException}，交给 P1-2 的
 *       Resilience4j 网关做「重试 → 熔断 → 降级」。
 *       这里绝不能吞：一旦吞成 {@code success=false}，重试与熔断就永远不会触发，
 *       平台故障时会静默返回「拉取 0 单」；</li>
 *   <li><b>不可重试的业务失败</b>（缺凭据 / 4xx 契约错）→ 捕获后返回
 *       {@code success=false} + {@code errorMsg}，让上游拿到可观测的失败摘要
 *       （HTTP 200 + 明确原因），而不是把 500 抛给运维。</li>
 * </ul>
 *
 * <p>两条分支互斥且都属正常业务路径，因此 P1-4 的契约测试对二者都有断言。
 */
@Component
@ConditionalOnProperty(name = "aslp.order.mock-enabled", havingValue = "false")
public class AmazonSpApiStrategy implements OrderPullStrategy {

    private static final Logger log = LoggerFactory.getLogger(AmazonSpApiStrategy.class);

    /** 平台标识（落库与日志口径与 M1 保持一致）。 */
    public static final String PLATFORM = "Amazon";

    private final SpApiProperties properties;
    private final SpApiOrderClient client;
    private final SpApiOrderMapper mapper;

    public AmazonSpApiStrategy(SpApiProperties properties, SpApiOrderClient client,
                               SpApiOrderMapper mapper) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public OrderPullResult pullOrders() {
        if (!properties.hasCredentials()) {
            // 不发任何网络请求：凭据缺失是可预期的部署形态（本地开发），
            // 不能让它变成一个「反复打真实平台」的故障源
            String message = "Amazon SP-API credentials not configured（缺少 "
                    + properties.missingCredentials()
                    + "；如需跳过真实平台可设 aslp.order.mock-enabled=true）";
            log.warn("[SP-API][契约] {}", message);
            return new OrderPullResult(PLATFORM, List.of(), false, message);
        }

        SpApiFetchResult fetched;
        try {
            fetched = client.fetchOrders();
        } catch (PlatformUnavailableException e) {
            // 429/5xx/超时：值得重试 —— 抛给 Resilience4j，不要在这里吞掉
            log.warn("[SP-API][契约] 平台暂时不可用，交由容错网关重试：{}", e.toString());
            throw e;
        } catch (SpApiClientException e) {
            // 4xx 契约/授权错：重试无意义，转成可观测的业务失败
            log.error("[SP-API][契约] 平台拒绝请求（不可重试）：{}", e.getMessage());
            return new OrderPullResult(PLATFORM, List.of(), false, e.getMessage());
        }

        if (fetched.truncated()) {
            // 「数据不完整」≠「失败」：订单照常入库，但要把「还有没拉完的」显式记录下来。
            // 注意 OrderPullService 只在失败时透出 errorMsg，成功路径的提示靠这里打日志（Loki 可查）
            String note = "已达分页上限 " + properties.getMaxPages() + " 页，可能存在未拉取的订单";
            log.warn("[SP-API][契约] {}", note);
            return new OrderPullResult(PLATFORM, mapper.toDtos(fetched.orders()), true, note);
        }
        return new OrderPullResult(PLATFORM, mapper.toDtos(fetched.orders()), true, null);
    }
}
