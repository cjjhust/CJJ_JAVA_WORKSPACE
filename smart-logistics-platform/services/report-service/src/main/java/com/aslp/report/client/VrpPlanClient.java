package com.aslp.report.client;

import com.aslp.report.dto.VrpPlanSnapshot;
import com.aslp.report.config.ReportProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * VRP 求解客户端（M3 路线可视化的数据源）。
 *
 * <p>走 route-service 的 {@code POST /api/routes/optimize}（不带 body 即内置演示问题：
 * Bruchsal 总仓 → 4 城）。求解是计算型调用（jsprit 迭代搜索），与报表其他只读数据源不同 ——
 * 因此 {@code maxIterations} 压到 1000（演示拓扑下解质量已收敛，实测 <1s），
 * 保证在报表 {@link ReportProperties#getReadTimeout()} 3s 上限内稳定返回。
 *
 * <p><b>单体共存（P2-7）</b>：仅微服务拓扑装配；单体拓扑下由 app-monolith 的
 * {@code LocalVrpPlanSource} 进程内调用 route 模块，聚合层无感。
 */
@Component
@Profile("!monolith")
public class VrpPlanClient implements VrpPlanSource {

    public static final String SOURCE = "route-service";

    /** 演示问题的迭代上限：收敛质量与报表读超时（3s）之间的平衡点（实测 ~0.5s）。 */
    static final int DEMO_MAX_ITERATIONS = 1000;

    private final RestClient client;

    public VrpPlanClient(RestClient.Builder builder, ReportProperties props) {
        this.client = ReportHttp.build(builder, props.getRouteBaseUrl(), props);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public VrpPlanSnapshot fetch() {
        try {
            VrpPlanSnapshot plan = client.post()
                    .uri("/api/routes/optimize")
                    .body(Map.of("maxIterations", DEMO_MAX_ITERATIONS))
                    .retrieve()
                    .body(VrpPlanSnapshot.class);
            if (plan == null) {
                throw new IllegalStateException("响应体为空");
            }
            return plan;
        } catch (RestClientException | IllegalStateException e) {
            throw new ReportSourceUnavailableException(SOURCE, e);
        }
    }
}
