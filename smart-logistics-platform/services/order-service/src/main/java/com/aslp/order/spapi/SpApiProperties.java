package com.aslp.order.spapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1-4：Amazon SP-API 接入参数（配置前缀 {@code aslp.order.amazon}）。
 *
 * <p>默认值按「欧洲站 + Amazon.de」口径给出。容器 profile 会把 {@code endpoint} 与
 * {@code token-url} 指向本地 WireMock 契约桩容器（{@code aslp_wiremock}），
 * 这样「真实 HTTP 调用 + LWA 换令牌 + 分页 + 429 限流 + 超时」整条链路
 * 在 CI / 演示环境里都能跑通，而<b>不需要真实卖家账号</b>。
 *
 * <p><b>为什么超时必须显式配置</b>：默认的 JDK HTTP 客户端读超时是「无限等待」，
 * 平台侧僵死（TCP 连接建立成功但不返回响应）时线程会被一直拖住 ——
 * Resilience4j 的熔断/舱壁都救不了「线程被占住」这件事，只能靠超时。
 */
@ConfigurationProperties(prefix = "aslp.order.amazon")
public class SpApiProperties {

    /** SP-API 区域端点（欧洲站）。不带结尾斜杠：客户端用相对路径拼接。 */
    private String endpoint = "https://sellingpartnerapi-eu.amazon.com";

    /** LWA（Login with Amazon）令牌端点。 */
    private String tokenUrl = "https://api.amazon.com/auth/o2/token";

    /** 站点市场 ID。Amazon.de = A1PA6795UKMFR9。 */
    private String marketplaceId = "A1PA6795UKMFR9";

    /** LWA 应用 client id。 */
    private String clientId;

    /** LWA 应用 client secret。 */
    private String clientSecret;

    /** 卖家授权得到的 refresh token（长期有效，用于换 access token）。 */
    private String refreshToken;

    /** 建连超时。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读超时。必须显式设置，理由见类注释。 */
    private Duration readTimeout = Duration.ofSeconds(5);

    /** 分页页数上限：防御性护栏，避免 NextToken 不收敛时无限翻页。 */
    private int maxPages = 5;

    /** 每页条数（SP-API 上限 100）。 */
    private int maxResultsPerPage = 50;

    /** 只拉最近 N 天的订单（SP-API 要求 CreatedAfter，且最早不超过 30 天）。 */
    private int createdAfterDays = 3;

    /**
     * 是否逐个订单拉商品明细（{@code /orderItems}）。
     *
     * <p>SP-API 的订单列表接口<b>不含商品名</b>，只能逐单再查一次（N+1）。
     * 关掉则商品名回退为占位符，可减少平台调用配额消耗。
     */
    private boolean fetchItemTitles = true;

    /** 目的地城市（小写）→ 履约仓展示名。未命中则用 {@link #defaultWarehouse}。 */
    private Map<String, String> warehouseByCity = new LinkedHashMap<>();

    /** 城市未命中映射表时的兜底履约仓。 */
    private String defaultWarehouse = "Bruchsal";

    /** access_token 提前失效的时钟偏移，避免临界过期导致一次无谓的 401 重试。 */
    private Duration tokenSkew = Duration.ofSeconds(60);

    /** 凭据是否齐全（不全时不应该发起任何网络调用）。 */
    public boolean hasCredentials() {
        return missingCredentialNames().isEmpty();
    }

    /** 缺失的凭据名（给运维直接可行动的提示）。 */
    public List<String> missingCredentialNames() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(clientId)) {
            missing.add("client-id");
        }
        if (!StringUtils.hasText(clientSecret)) {
            missing.add("client-secret");
        }
        if (!StringUtils.hasText(refreshToken)) {
            missing.add("refresh-token");
        }
        return missing;
    }

    /** 缺失凭据的逗号串（用于错误信息）。 */
    public String missingCredentials() {
        return String.join(", ", missingCredentialNames());
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public String getMarketplaceId() {
        return marketplaceId;
    }

    public void setMarketplaceId(String marketplaceId) {
        this.marketplaceId = marketplaceId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(int maxPages) {
        this.maxPages = maxPages;
    }

    public int getMaxResultsPerPage() {
        return maxResultsPerPage;
    }

    public void setMaxResultsPerPage(int maxResultsPerPage) {
        this.maxResultsPerPage = maxResultsPerPage;
    }

    public int getCreatedAfterDays() {
        return createdAfterDays;
    }

    public void setCreatedAfterDays(int createdAfterDays) {
        this.createdAfterDays = createdAfterDays;
    }

    public boolean isFetchItemTitles() {
        return fetchItemTitles;
    }

    public void setFetchItemTitles(boolean fetchItemTitles) {
        this.fetchItemTitles = fetchItemTitles;
    }

    public Map<String, String> getWarehouseByCity() {
        return warehouseByCity;
    }

    public void setWarehouseByCity(Map<String, String> warehouseByCity) {
        this.warehouseByCity = warehouseByCity;
    }

    public String getDefaultWarehouse() {
        return defaultWarehouse;
    }

    public void setDefaultWarehouse(String defaultWarehouse) {
        this.defaultWarehouse = defaultWarehouse;
    }

    public Duration getTokenSkew() {
        return tokenSkew;
    }

    public void setTokenSkew(Duration tokenSkew) {
        this.tokenSkew = tokenSkew;
    }
}
