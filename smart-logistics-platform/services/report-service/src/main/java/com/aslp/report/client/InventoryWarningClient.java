package com.aslp.report.client;

import com.aslp.report.config.ReportProperties;
import com.aslp.report.dto.InventoryWarningSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 库存预警客户端（M5 报表的数据源之二）。
 *
 * <p>复用 inventory-service 已有的 {@code /api/inventory/warnings/status}（P1-5 交付）：
 * 阈值口径、低库存判定只有一处实现，报表不重复造。
 *
 * <p>边界同 {@link OrderStatsClient}：报表只读契约，不碰 inventory 表。
 */
@Component
public class InventoryWarningClient {

    /** 数据源标识：会原样出现在报表响应的 {@code unavailable} 列表里。 */
    public static final String SOURCE = "inventory-service";

    private final RestClient client;

    public InventoryWarningClient(RestClient.Builder builder, ReportProperties props) {
        this.client = ReportHttp.build(builder, props.getInventoryBaseUrl(), props);
    }

    /**
     * 拉取库存预警状态。
     *
     * @throws ReportSourceUnavailableException 连接失败 / 超时 / 4xx / 5xx / 空响应体
     */
    public InventoryWarningSnapshot fetch() {
        try {
            InventoryWarningSnapshot snapshot = client.get()
                    .uri("/api/inventory/warnings/status")
                    .retrieve()
                    .body(InventoryWarningSnapshot.class);
            if (snapshot == null) {
                throw new IllegalStateException("响应体为空");
            }
            return snapshot;
        } catch (RestClientException | IllegalStateException e) {
            throw new ReportSourceUnavailableException(SOURCE, e);
        }
    }
}
