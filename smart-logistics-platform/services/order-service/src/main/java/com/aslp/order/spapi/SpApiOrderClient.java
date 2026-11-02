package com.aslp.order.spapi;

import com.aslp.order.strategy.PlatformUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon SP-API 订单接口客户端（P1-4）。
 *
 * <p>对齐的官方契约：
 * <pre>
 *   POST {token-url}                         LWA 换 access_token（表单，见 LwaTokenClient）
 *   GET  /orders/v0/orders                  列出订单（NextToken 分页）
 *   GET  /orders/v0/orders/{orderId}/orderItems   单个订单的商品明细
 * </pre>
 * 鉴权头 {@code x-amz-access-token}；时间参数 {@code CreatedAfter} 为 ISO-8601（UTC）。
 *
 * <p><b>失败分类 —— 本类最核心的契约</b>：
 * <table border="1">
 *   <caption>状态码到异常/重试语义的映射</caption>
 *   <tr><th>平台返回</th><th>抛出的异常</th><th>是否重试</th><th>理由</th></tr>
 *   <tr><td>429</td><td>{@link RateLimitedException}</td><td>是</td><td>平台限流，退避后确实可能成功</td></tr>
 *   <tr><td>5xx</td><td>{@link PlatformUnavailableException}</td><td>是</td><td>平台侧故障，与自家代码无关</td></tr>
 *   <tr><td>连接/读取超时</td><td>{@link PlatformUnavailableException}</td><td>是</td><td>网络抖动（读取超时由显式 read-timeout 触发）</td></tr>
 *   <tr><td>401</td><td>先刷新令牌重试一次；仍 401 → {@link SpApiClientException}</td><td>一次</td><td>令牌过期可救，凭据错不可救</td></tr>
 *   <tr><td>其他 4xx</td><td>{@link SpApiClientException}</td><td>否</td><td>参数/授权错，重试只会打光配额</td></tr>
 * </table>
 *
 * <p><b>分页终止条件</b>：{@code payload.NextToken} 为 null/缺省。
 * 同时用 {@code max-pages} 做防御性上限 —— 平台侧 bug 导致 NextToken 永不收敛时，
 * 不能让拉取任务无限翻页（这是「外包平台」类系统最常见的雪崩入口）。
 */
public final class SpApiOrderClient {

    private static final Logger log = LoggerFactory.getLogger(SpApiOrderClient.class);

    /** 订单列表路径（与官方文档一致）。 */
    static final String ORDERS_PATH = "/orders/v0/orders";

    /** 商品明细路径模板。 */
    static final String ORDER_ITEMS_PATH = "/orders/v0/orders/{orderId}/orderItems";

    /** SP-API 鉴权头。 */
    static final String ACCESS_TOKEN_HEADER = "x-amz-access-token";

    /** 限流退避建议头（用字面量：避免不同 Spring 小版本常量缺失）。 */
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    /** 429 未带 Retry-After 时的兜底退避秒数。 */
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 1L;

    private final SpApiProperties props;
    private final LwaTokenClient tokens;
    private final RestClient ordersClient;

    /**
     * P2-7：标题进程内缓存（orderId → (title, 写入时刻)）。
     *
     * <p>订单的商品名在履约期间几乎不变，而 5 分钟定时任务与手动补拉会反复
     * 拉到同一批「已拉过明细」的订单 —— 不缓存意味着每 5 分钟对平台重复
     * N 次 /orderItems 调用，纯粹烧配额。用 {@link LinkedHashMap} 的
     * access-order + removeEldestEntry 做 LRU（十几行，不引 Caffeine），
     * TTL 过期即失效（与 TrackingService 缓存同一思路：缓存放使用方而不是平台）。
     */
    private final Map<String, CachedTitle> titleCache =
            new LinkedHashMap<>(64, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedTitle> eldest) {
                    // 容量与 TTL 取小：条目再多也只占这点内存，过旧即丢
                    return size() > 512;
                }
            };

    private record CachedTitle(String title, Instant writtenAt) {
    }

    public SpApiOrderClient(SpApiProperties props, LwaTokenClient tokens, RestClient.Builder builder) {
        this.props = props;
        this.tokens = tokens;
        this.ordersClient = SpApiHttp.configure(builder, props)
                .baseUrl(props.getEndpoint())
                .build();
    }

    /** 按配置的站点拉取订单。 */
    public SpApiFetchResult fetchOrders() {
        return fetchOrders(props.getMarketplaceId());
    }

    /**
     * 按指定站点拉取订单（可覆盖 —— 诊断探针用不同 MarketplaceId 命中不同的桩场景）。
     */
    public SpApiFetchResult fetchOrders(String marketplaceId) {
        List<SpApiOrder> collected = new ArrayList<>();
        String nextToken = null;
        int pages = 0;
        boolean truncated = false;

        do {
            SpApiOrdersPage page = fetchPage(marketplaceId, nextToken);
            pages++;
            collected.addAll(extractOrders(page));
            nextToken = nextTokenOf(page);
            if (StringUtils.hasText(nextToken) && pages >= props.getMaxPages()) {
                truncated = true;
                log.warn("[SP-API][契约] 已达分页上限 {} 页，仍存在 NextToken —— 本轮结果可能不完整",
                        props.getMaxPages());
                break;
            }
        } while (StringUtils.hasText(nextToken));

        if (props.isFetchItemTitles()) {
            collected = enrichWithTitles(collected);
        }
        log.info("[SP-API][契约] 拉取完成：站点 {}，页数 {}，订单 {} 条，截断 {}",
                marketplaceId, pages, collected.size(), truncated);
        return new SpApiFetchResult(List.copyOf(collected), pages, truncated);
    }

    /** 拉一页（401 时刷新令牌重试一次）。 */
    private SpApiOrdersPage fetchPage(String marketplaceId, String nextToken) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return callOrders(marketplaceId, nextToken);
            } catch (UnauthorizedException e) {
                if (attempt > 0) {
                    throw new SpApiClientException(
                            "SP-API 在刷新令牌后仍返回 401：refresh-token 已失效或应用被撤销授权，需重新授权");
                }
                log.warn("[SP-API][契约] 收到 401，主动刷新 LWA 令牌后重试一次");
                tokens.invalidate();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private SpApiOrdersPage callOrders(String marketplaceId, String nextToken) {
        String token = tokens.accessToken();
        try {
            SpApiOrdersPage page = ordersClient.get()
                    .uri(builder -> {
                        builder.path(ORDERS_PATH)
                                .queryParam("MarketplaceIds", marketplaceId)
                                .queryParam("CreatedAfter", createdAfter())
                                .queryParam("MaxResultsPerPage", props.getMaxResultsPerPage());
                        if (StringUtils.hasText(nextToken)) {
                            builder.queryParam("NextToken", nextToken);
                        }
                        return builder.build();
                    })
                    .header(ACCESS_TOKEN_HEADER, token)
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (request, response) -> {
                        throw new UnauthorizedException();
                    })
                    .onStatus(status -> status.value() == 429, (request, response) -> {
                        throw new RateLimitedException("SP-API 限流（HTTP 429）", retryAfterSeconds(response));
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new PlatformUnavailableException(
                                "SP-API 服务端错误（HTTP " + response.getStatusCode().value() + "）");
                    })
                    .onStatus(status -> status.is4xxClientError()
                                    && status.value() != 401 && status.value() != 429,
                            (request, response) -> {
                                throw new SpApiClientException(
                                        "SP-API 拒绝请求（HTTP " + response.getStatusCode().value()
                                                + "）：请求参数/授权有问题，重试无意义");
                            })
                    .body(SpApiOrdersPage.class);
            return page == null ? SpApiOrdersPage.EMPTY : page;
        } catch (ResourceAccessException e) {
            throw new PlatformUnavailableException(
                    "SP-API 连接或读取超时（connect=" + props.getConnectTimeout()
                            + ", read=" + props.getReadTimeout() + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 补商品名（明细失败不影响主流程：商品名回退占位符）。
     *
     * <p><b>P2-7 两条护栏</b>：
     * <ol>
     *   <li><b>单轮上限</b>：一次拉取最多对 {@code title-enrich-max-orders}（默认 30）条订单
     *       发起 /orderItems 调用。SP-API 配额按「每卖家每分钟」计量，明细调用是配额放大器
     *       （5 页 × 50 条 = 250 次，比订单列表本身多 5 倍）—— 非关键调用把配额打满后
     *       连订单列表都会被 429。超限的订单本轮回退占位符，<b>下一轮</b>再补
     *       （订单会持续被重新拉取，商品名最终补齐，只是晚几轮）。</li>
     *   <li><b>进程内 LRU 缓存</b>：TTL 内的订单不再重复请求平台
     *       （定时任务与手动补拉之间的重叠不再烧配额）。</li>
     * </ol>
     */
    private List<SpApiOrder> enrichWithTitles(List<SpApiOrder> orders) {
        int budget = Math.max(1, props.getTitleEnrichMaxOrders());
        List<SpApiOrder> enriched = new ArrayList<>(orders.size());
        int fetched = 0;
        int cached = 0;
        for (SpApiOrder order : orders) {
            SpApiOrder next = order;

            CachedTitle hit = titleCache.get(order.orderId());
            if (hit != null && isFresh(hit)) {
                next = order.withTitle(hit.title());
                cached++;
            } else if (fetched < budget) {
                fetched++;
                next = withTitle(order);
                if (StringUtils.hasText(next.title())) {
                    titleCache.put(order.orderId(), new CachedTitle(next.title(), Instant.now()));
                }
            } else {
                log.debug("[SP-API][契约] 订单 {} 本轮超出标题富集上限（{}/{}），商品名回退占位符，下一轮再补",
                        order.orderId(), fetched, budget);
            }
            enriched.add(next);
        }
        if (cached > 0 || fetched < orders.size()) {
            log.info("[SP-API][契约] 标题富集：本批 {} 条，缓存命中 {} 条，实际请求 {} 条，上限 {}",
                    orders.size(), cached, fetched, budget);
        }
        return enriched;
    }

    private boolean isFresh(CachedTitle entry) {
        return entry.writtenAt()
                .plus(props.getTitleCacheTtl())
                .isAfter(Instant.now());
    }

    private SpApiOrder withTitle(SpApiOrder order) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return fetchTitle(order);
            } catch (UnauthorizedException e) {
                if (attempt > 0) {
                    log.warn("[SP-API][契约] 订单 {} 明细接口刷新令牌后仍 401，商品名回退占位符", order.orderId());
                    return order;
                }
                tokens.invalidate();
            } catch (RuntimeException e) {
                // 明细是「锦上添花」：平台 4xx/5xx/超时都不应该让整批订单拉取失败
                log.warn("[SP-API][契约] 订单 {} 商品明细获取失败（不影响主流程，商品名回退占位符）：{}",
                        order.orderId(), e.toString());
                return order;
            }
        }
        return order;
    }

    private SpApiOrder fetchTitle(SpApiOrder order) {
        SpApiOrderItemsPage page = ordersClient.get()
                .uri(builder -> builder.path(ORDER_ITEMS_PATH).build(order.orderId()))
                .header(ACCESS_TOKEN_HEADER, tokens.accessToken())
                .retrieve()
                .onStatus(status -> status.value() == 401, (request, response) -> {
                    throw new UnauthorizedException();
                })
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new SpApiClientException("SP-API 商品明细接口返回 HTTP "
                            + response.getStatusCode().value());
                })
                .body(SpApiOrderItemsPage.class);

        String title = firstTitle(page);
        return StringUtils.hasText(title) ? order.withTitle(title) : order;
    }

    /** {@code CreatedAfter}：ISO-8601 UTC 时刻（SP-API 只接受这个口径）。 */
    private String createdAfter() {
        return Instant.now()
                .minus(Math.max(1, props.getCreatedAfterDays()), ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    private static List<SpApiOrder> extractOrders(SpApiOrdersPage page) {
        if (page == null || page.payload() == null || page.payload().orders() == null) {
            return List.of();
        }
        List<SpApiOrder> result = new ArrayList<>();
        for (SpApiOrdersPage.RawOrder raw : page.payload().orders()) {
            if (raw == null || !StringUtils.hasText(raw.amazonOrderId())) {
                log.warn("[SP-API][契约] 跳过缺少 AmazonOrderId 的订单条目（平台报文异常）");
                continue;
            }
            SpApiOrdersPage.ShippingAddress address = raw.shippingAddress();
            result.add(new SpApiOrder(
                    raw.amazonOrderId(),
                    raw.orderStatus(),
                    address == null ? null : address.city(),
                    address == null ? null : address.countryCode(),
                    null));
        }
        return result;
    }

    private static String nextTokenOf(SpApiOrdersPage page) {
        return page == null || page.payload() == null ? null : page.payload().nextToken();
    }

    private static String firstTitle(SpApiOrderItemsPage page) {
        if (page == null || page.payload() == null || page.payload().orderItems() == null
                || page.payload().orderItems().isEmpty()) {
            return null;
        }
        return page.payload().orderItems().get(0).title();
    }

    /** 解析 Retry-After（秒；缺失或非法时兜底 1s）。 */
    private static long retryAfterSeconds(ClientHttpResponse response) {
        String header = response.getHeaders().getFirst(RETRY_AFTER_HEADER);
        if (!StringUtils.hasText(header)) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
        try {
            return Math.max(1L, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_AFTER_SECONDS;
        }
    }

    /** 401 的内部信号：只在 fetchPage / withTitle 内部流转，不对外暴露。 */
    private static final class UnauthorizedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
