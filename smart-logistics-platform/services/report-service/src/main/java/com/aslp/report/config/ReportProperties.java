package com.aslp.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * M5 报表服务配置（下游数据源地址与超时）。
 *
 * <p><b>为什么地址放在配置里而不是代码里</b>：本地开发直连 {@code localhost:8081}，
 * 容器内必须走连字符网络别名（{@code http://aslp-order-service:8081}，
 * 下划线主机名在 URI 层非法，见 readme §9 #21/#30/#35）。
 * 同一份代码在两个 profile 下运行，差异只能在配置层表达。
 *
 * <p><b>超时必须有显式上限</b>：这些调用位于「前端随时会点开的看板」路径上，
 * 默认的无限读超时会让一个卡住的上游把报表线程全部占死 —— 那比「报表降级显示」糟糕得多。
 */
@ConfigurationProperties(prefix = "aslp.report")
public class ReportProperties {

    /** 订单服务基地址（提供 /api/orders/stats）。 */
    private String orderBaseUrl = "http://localhost:8081";

    /** 库存服务基地址（提供 /api/inventory/warnings/status）。 */
    private String inventoryBaseUrl = "http://localhost:8082";

    /** 建连超时（下游没在监听时必须快速失败，而不是让报表页面转圈）。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读取超时。 */
    private Duration readTimeout = Duration.ofSeconds(3);

    /** 看板里低库存明细最多展示几条（防止一个仓库几百个低库存 SKU 把响应撑爆）。 */
    private int lowStockLimit = 20;

    public String getOrderBaseUrl() {
        return orderBaseUrl;
    }

    public void setOrderBaseUrl(String orderBaseUrl) {
        this.orderBaseUrl = orderBaseUrl;
    }

    public String getInventoryBaseUrl() {
        return inventoryBaseUrl;
    }

    public void setInventoryBaseUrl(String inventoryBaseUrl) {
        this.inventoryBaseUrl = inventoryBaseUrl;
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

    public int getLowStockLimit() {
        return lowStockLimit;
    }

    public void setLowStockLimit(int lowStockLimit) {
        this.lowStockLimit = lowStockLimit;
    }
}
