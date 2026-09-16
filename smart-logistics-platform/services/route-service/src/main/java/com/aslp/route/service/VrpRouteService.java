package com.aslp.route.service;

import com.graphhopper.jsprit.core.algorithm.VehicleRoutingAlgorithm;
import com.graphhopper.jsprit.core.algorithm.SearchStrategyManager;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.solution.VehicleRoutingProblemSolution;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TourActivity;
import com.graphhopper.jsprit.core.util.Solutions;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
public class VrpRouteService {

    /**
     * 使用 jsprit 计算最优配送路线（欧洲 DHL/DPD 路线模拟）
     */
    public String calculateOptimalRoute(VehicleRoutingProblem problem) {
        SearchStrategyManager strategy = new SearchStrategyManager();
        VehicleRoutingAlgorithm algorithm = new VehicleRoutingAlgorithm(problem, strategy);
        Collection<VehicleRoutingProblemSolution> solutions = algorithm.searchSolutions();
        VehicleRoutingProblemSolution best = Solutions.bestOf(solutions);
        return best != null ? best.toString() : "无可行路线";
    }
}
