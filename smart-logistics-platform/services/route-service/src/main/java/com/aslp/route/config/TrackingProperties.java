package com.aslp.route.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * M3 尾程追踪配置（{@code aslp.tracking.*}）。
 *
 * <p>与 order-service 的 {@code aslp.order.amazon.*} 同一套思路：**端点是配置、凭据是环境变量**。
 * 本地开发可以指向真实端点（没有 key 会得到 401/403，这是诚实的结果），
 * 容器/CI 指向 WireMock 契约桩（见 {@code application-docker.yml}）。
 *
 * <p>{@code mockEnabled} 默认为 true：与 {@code aslp.order.mock-enabled} 一致 ——
 * 没有生产凭据时流水线仍然可测、可演示，而不是"跑起来就报错"。
 */
@ConfigurationProperties(prefix = "aslp.tracking")
public class TrackingProperties {

    /** true = 使用内置确定性 Mock（无凭证也能演示）；false = 真实 HTTP 调用承运商接口。 */
    private boolean mockEnabled = true;

    /** DHL Unified Shipment Tracking 基地址。 */
    private String dhlBaseUrl = "https://api-eu.dhl.com";

    /** DHL API Key（生产从环境变量注入，不写进仓库）。 */
    private String dhlApiKey = "";

    /** DPD 追踪基地址。 */
    private String dpdBaseUrl = "https://api.dpd.com";

    /** DPD API Key。 */
    private String dpdApiKey = "";

    /** 建连超时（承运商网络不可达时必须快速失败，不能拖住调用方线程）。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读取超时。 */
    private Duration readTimeout = Duration.ofSeconds(5);

    /**
     * 查询结果本地缓存时长。
     *
     * <p>为什么需要：承运商接口有配额/限流，而包裹状态本身变化很慢（分钟级）。
     * 面客页面刷新一次就打一次上游既浪费配额也增加尾延迟。
     * 需要强实时时用 {@code ?refresh=true} 绕过（例如客服刚打过电话）。
     */
    private Duration cacheTtl = Duration.ofSeconds(60);

    /** 缓存最大条目数（LRU 淘汰）—— 无界缓存是最常见的内存泄漏来源。 */
    private int maxCacheEntries = 1000;

    /** 事件列表最多返回条数（承运商会返回整条历史，面客只需要近期节点）。 */
    private int maxEvents = 30;

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    public void setMockEnabled(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    public String getDhlBaseUrl() {
        return dhlBaseUrl;
    }

    public void setDhlBaseUrl(String dhlBaseUrl) {
        this.dhlBaseUrl = dhlBaseUrl;
    }

    public String getDhlApiKey() {
        return dhlApiKey;
    }

    public void setDhlApiKey(String dhlApiKey) {
        this.dhlApiKey = dhlApiKey;
    }

    public String getDpdBaseUrl() {
        return dpdBaseUrl;
    }

    public void setDpdBaseUrl(String dpdBaseUrl) {
        this.dpdBaseUrl = dpdBaseUrl;
    }

    public String getDpdApiKey() {
        return dpdApiKey;
    }

    public void setDpdApiKey(String dpdApiKey) {
        this.dpdApiKey = dpdApiKey;
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

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public int getMaxCacheEntries() {
        return maxCacheEntries;
    }

    public void setMaxCacheEntries(int maxCacheEntries) {
        this.maxCacheEntries = maxCacheEntries;
    }

    public int getMaxEvents() {
        return maxEvents;
    }

    public void setMaxEvents(int maxEvents) {
        this.maxEvents = maxEvents;
    }
}
