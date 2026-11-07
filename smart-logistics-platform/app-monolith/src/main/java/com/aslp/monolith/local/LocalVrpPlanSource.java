package com.aslp.monolith.local;

import com.aslp.route.dto.VrpPlan;
import com.aslp.report.dto.VrpPlanSnapshot;
import com.aslp.route.engine.VrpProblemFactory;
import com.aslp.route.service.VrpRouteService;
import com.aslp.report.client.ReportSourceUnavailableException;
import com.aslp.report.client.VrpPlanSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * VRP 求解的<b>进程内</b>数据源（单体拓扑专用，P2-7）。
 *
 * <p>微服务拓扑下同一份数据走 {@code POST /api/routes/optimize}（{@code VrpPlanClient}）；
 * 单体拓扑下直接注入 route 模块的求解器，跳过 HTTP 环路。
 * 迭代上限与 HTTP 侧一致（{@code VrpPlanClient#DEMO_MAX_ITERATIONS}），
 * 两种拓扑的解质量与耗时表现对齐。
 */
@Component
@Profile("monolith")
public class LocalVrpPlanSource implements VrpPlanSource {

    private final VrpProblemFactory factory;
    private final VrpRouteService solver;

    public LocalVrpPlanSource(VrpProblemFactory factory, VrpRouteService solver) {
        this.factory = factory;
        this.solver = solver;
    }

    @Override
    public String getSource() {
        return "route-service";
    }

    @Override
    public VrpPlanSnapshot fetch() {
        try {
            VrpProblemFactory.Spec spec = factory.demo();
            VrpPlan plan = solver.solve(spec.problem(), spec.maxIterations());
            return toSnapshot(plan);
        } catch (RuntimeException e) {
            throw new ReportSourceUnavailableException(getSource(), e);
        }
    }

    /** 单体侧 {@code VrpPlan} 与报表侧快照字段同名同义，逐一映射（新增字段需两侧同步）。 */
    private static VrpPlanSnapshot toSnapshot(VrpPlan plan) {
        return new VrpPlanSnapshot(
                plan.feasible(), plan.message(), plan.totalDistanceKm(), plan.totalLoad(),
                plan.routeCount(), plan.stopCount(), plan.unassignedJobIds(), plan.elapsedMs(),
                plan.routes().stream().map(r -> new VrpPlanSnapshot.Route(
                        r.routeId(), r.vehicleId(), r.depotId(), r.distanceKm(), r.load(),
                        r.stops().stream().map(s -> new VrpPlanSnapshot.Stop(
                                s.sequence(), s.jobId(), s.name(), s.lat(), s.lon(),
                                s.demand(), s.arrivalHours(), s.kind())).toList())).toList());
    }
}
