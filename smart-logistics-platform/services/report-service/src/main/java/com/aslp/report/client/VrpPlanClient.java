package com.aslp.report.client;

import com.aslp.report.dto.VrpPlanSnapshot;
import com.aslp.report.config.ReportProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
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
            // 必须发完整请求体：route-service 收到「任何 body」（哪怕只有 maxIterations）
            // 都不会再回落内置演示问题，缺 depot/vehicles 会直接 400（容器实测踩中）。
            // 因此这里完整复刻演示拓扑（与 VrpProblemFactory.demo() 同源），并压迭代数。
            VrpPlanSnapshot plan = client.post()
                    .uri("/api/routes/optimize")
                    .body(Map.of(
                            "depot", Map.of("id", "Bruchsal-总仓", "lat", 49.1243, "lon", 8.5987),
                            "vehicles", List.of(
                                    Map.of("id", "V-01", "capacity", 20),
                                    Map.of("id", "V-02", "capacity", 20)),
                            "deliveries", List.of(
                                    Map.of("id", "VRP-D-01", "name", "Karlsruhe", "lat", 49.0069, "lon", 8.4037, "demand", 3),
                                    Map.of("id", "VRP-D-02", "name", "Frankfurt", "lat", 50.1109, "lon", 8.6821, "demand", 5),
                                    Map.of("id", "VRP-D-03", "name", "Düsseldorf", "lat", 51.2277, "lon", 6.7735, "demand", 2),
                                    Map.of("id", "VRP-D-04", "name", "Mönchengladbach", "lat", 51.1805, "lon", 6.4428, "demand", 4)),
                            "maxIterations", DEMO_MAX_ITERATIONS))
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
