package com.aslp.order.spapi;

import com.aslp.order.strategy.PlatformUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.concurrent.atomic.AtomicReference;

/**
 * LWA（Login with Amazon）访问令牌客户端（P1-4）。
 *
 * <p>SP-API 的鉴权是两步式：先用长期有效的 {@code refresh_token} 换短期
 * {@code access_token}，再把它放进 {@code x-amz-access-token} 头。因此
 * <b>换令牌的失败语义直接决定整条拉取链路的容错表现</b>：
 * <ul>
 *   <li>400 / 401（client id / secret / refresh token 错、应用未授权）→
 *       {@link SpApiClientException}：<b>配置问题，重试无意义</b>；</li>
 *   <li>429 / 5xx / 连接或读取超时 → {@link PlatformUnavailableException}：
 *       可重试，交给 P1-2 的 Resilience4j 链路。</li>
 * </ul>
 *
 * <p><b>令牌缓存</b>：access_token 默认有效期 1 小时。若每次分页/每单都换一次令牌，
 * 既浪费一个来回，也极易触发 LWA 侧限流。这里做进程内缓存并提前
 * {@code aslp.order.amazon.token-skew}（默认 60s）失效，避免临界过期。
 */
public final class LwaTokenClient {

    private static final Logger log = LoggerFactory.getLogger(LwaTokenClient.class);

    /** 平台未给出 expires_in 时的兜底有效期（秒）。 */
    private static final long DEFAULT_TTL_SECONDS = 3600L;

    private final SpApiProperties props;
    private final RestClient restClient;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public LwaTokenClient(SpApiProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = SpApiHttp.configure(builder, props).build();
    }

    /** 取可用令牌：命中缓存直接返回，否则换取新令牌。 */
    public String accessToken() {
        Cached current = cache.get();
        if (current != null && current.usable()) {
            return current.value();
        }
        return refresh();
    }

    /** 强制换取新令牌（并发下只换一次）。 */
    public synchronized String refresh() {
        Cached current = cache.get();
        if (current != null && current.usable()) {
            return current.value();
        }
        LwaTokenResponse response = requestToken();
        String token = response == null ? null : response.accessToken();
        if (!StringUtils.hasText(token)) {
            throw new SpApiClientException("LWA 响应缺少 access_token：令牌端点契约不符（检查是否被网关/代理改写）");
        }
        long expiresIn = response.expiresIn() > 0 ? response.expiresIn() : DEFAULT_TTL_SECONDS;
        long skewSeconds = Math.max(0, props.getTokenSkew().toSeconds());
        long ttlSeconds = Math.max(1, expiresIn - skewSeconds);
        cache.set(new Cached(token, System.nanoTime() + ttlSeconds * 1_000_000_000L));
        log.debug("[SP-API][LWA] 已换取访问令牌，有效期 {}s（提前 {}s 失效）", ttlSeconds, skewSeconds);
        return token;
    }

    /** 让缓存立即失效（收到 401 时调用：令牌可能被平台提前吊销）。 */
    public void invalidate() {
        cache.set(null);
    }

    /** 当前是否持有尚未过期的令牌（诊断探针用，不触发网络调用）。 */
    public boolean tokenCached() {
        Cached current = cache.get();
        return current != null && current.usable();
    }

    private LwaTokenResponse requestToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", props.getRefreshToken());
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());
        try {
            return restClient.post()
                    .uri(props.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(LwaTokenResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || e.getStatusCode().is5xxServerError()) {
                throw new PlatformUnavailableException(
                        "LWA 令牌端点暂时不可用（HTTP " + status + "）", e);
            }
            throw new SpApiClientException(
                    "LWA 拒绝换令牌（HTTP " + status + "）：client-id / client-secret / refresh-token "
                            + "或应用授权配置有误，重试无意义", e);
        } catch (ResourceAccessException e) {
            throw new PlatformUnavailableException("LWA 令牌端点连接/读取超时：" + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new PlatformUnavailableException("LWA 令牌换取失败：" + e.getMessage(), e);
        }
    }

    /** LWA 换令牌响应（蛇形命名是平台契约，必须显式声明）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LwaTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType) {
    }

    /** 进程内令牌缓存条目。 */
    private record Cached(String value, long expiresAtNanos) {
        boolean usable() {
            return System.nanoTime() < expiresAtNanos;
        }
    }
}
