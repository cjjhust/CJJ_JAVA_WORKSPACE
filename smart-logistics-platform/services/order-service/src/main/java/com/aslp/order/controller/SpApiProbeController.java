package com.aslp.order.controller;

import com.aslp.order.spapi.LwaTokenClient;
import com.aslp.order.spapi.RateLimitedException;
import com.aslp.order.spapi.SpApiClientException;
import com.aslp.order.spapi.SpApiFetchResult;
import com.aslp.order.spapi.SpApiOrderClient;
import com.aslp.order.spapi.SpApiOrderMapper;
import com.aslp.order.spapi.SpApiProperties;
import com.aslp.order.strategy.PlatformUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-4 平台契约诊断探针。
 *
 * <p><b>为什么需要一个探针端点</b>：契约测试（WireMock）能证明「代码与桩一致」，
 * 但证明不了「打包进镜像、跑在容器网络里、真正发 HTTP 出去」这一段。
 * 平台接口契约失效（字段改名、限流策略变更、令牌流程调整）是这类系统最典型的
 * 线上事故来源，必须有一个<b>无副作用</b>的入口随时体检。
 *
 * <p><b>无副作用保证</b>：只调用 SP-API 客户端 + 映射器，<b>不落库、不写指标、
 * 不经过熔断器</b> —— 因此可以随时对生产调用，不会污染订单表，也不会因为探针自身
 * 失败把熔断器推向打开。
 *
 * <p><b>为什么用不同地方案（MarketplaceId）区分故障场景</b>：WireMock 桩按
 * {@code MarketplaceIds} 查询参数分流，于是「正常 / 限流 / 超时 / 5xx / 4xx / 空结果」
 * 六种契约场景可以共用一个端点，端到端断言无需为每种故障重启容器。
 *
 * @see com.aslp.order.spapi.SpApiOrderClient
 */
@RestController
@RequestMapping("/api/orders/spapi")
public class SpApiProbeController {

    private static final Logger log = LoggerFactory.getLogger(SpApiProbeController.class);

    /** 凭据缺失时的错误类型（探针不因此发起网络调用）。 */
    static final String TYPE_CREDENTIALS_MISSING = "CredentialsMissing";

    /** 空结果场景占位（仅用于可读性，不影响判定）。 */
    static final String TYPE_OK = "ok";

    private final SpApiProperties properties;
    private final SpApiOrderClient client;
    private final SpApiOrderMapper mapper;
    private final LwaTokenClient tokens;

    public SpApiProbeController(SpApiProperties properties, SpApiOrderClient client,
                                SpApiOrderMapper mapper, LwaTokenClient tokens) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
        this.tokens = tokens;
    }

    /**
     * 单次拉取探针。
     *
     * @param marketplaceId 可选：覆盖站点 ID，用于命中不同的契约桩场景
     * @return 统一的诊断报文（HTTP 恒为 200：探针的「失败」是业务信息而不是 HTTP 错误）
     */
    @PostMapping("/probe")
    public ResponseEntity<Map<String, Object>> probe(
            @RequestParam(required = false) String marketplaceId) {

        String site = (marketplaceId == null || marketplaceId.isBlank())
                ? properties.getMarketplaceId() : marketplaceId.trim();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", properties.hasCredentials());
        body.put("endpoint", properties.getEndpoint());
        body.put("marketplaceId", site);
        body.put("readTimeoutMs", properties.getReadTimeout().toMillis());
        body.put("tokenCachedBefore", tokens.tokenCached());

        if (!properties.hasCredentials()) {
            body.put("ok", false);
            body.put("errorType", TYPE_CREDENTIALS_MISSING);
            body.put("retryable", false);
            body.put("errorMsg", "缺少凭据：" + properties.missingCredentials());
            return ResponseEntity.ok(body);
        }

        long startedAt = System.nanoTime();
        try {
            SpApiFetchResult result = client.fetchOrders(site);
            body.put("ok", true);
            body.put("type", TYPE_OK);
            body.put("pages", result.pages());
            body.put("truncated", result.truncated());
            body.put("count", result.orders().size());
            body.put("orders", mapper.toDtos(result.orders()));
        } catch (RateLimitedException e) {
            body.put("ok", false);
            body.put("errorType", "RateLimitedException");
            body.put("retryable", true);
            body.put("retryAfterSeconds", e.getRetryAfterSeconds());
            body.put("errorMsg", e.getMessage());
        } catch (PlatformUnavailableException e) {
            body.put("ok", false);
            body.put("errorType", "PlatformUnavailableException");
            body.put("retryable", true);
            body.put("errorMsg", e.getMessage());
        } catch (SpApiClientException e) {
            body.put("ok", false);
            body.put("errorType", "SpApiClientException");
            body.put("retryable", false);
            body.put("errorMsg", e.getMessage());
        }
        body.put("elapsedMs", (System.nanoTime() - startedAt) / 1_000_000L);
        body.put("tokenCachedAfter", tokens.tokenCached());

        List<?> orders = (List<?>) body.getOrDefault("orders", List.of());
        log.info("[SP-API][探针] 站点 {} -> ok={} type={} 订单 {} 条",
                site, body.get("ok"), body.getOrDefault("type", body.get("errorType")), orders.size());
        return ResponseEntity.ok(body);
    }
}
