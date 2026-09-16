package com.aslp.route.engine;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.cost.AbstractForwardVehicleRoutingTransportCosts;
import com.graphhopper.jsprit.core.problem.driver.Driver;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;

/**
 * 真实里程成本模型：用 Haversine 大圆距离（km）替代 jsprit 的默认欧氏距离。
 *
 * <p><b>为什么必须自带成本模型</b>
 * <ul>
 *   <li>jsprit 默认（不设 {@code setRoutingCost}）用<b>坐标单位的欧氏距离</b>。
 *       我们的坐标是经纬度，于是“距离”变成<b>度</b>：Bruchsal→Karlsruhe 只有 0.19，
 *       而 Bruchsal→Mönchengladbach 是 3.6 —— 量纲错误，运费与里程都不可用。</li>
 *   <li>直接用 jsprit 内置的 {@code GreatCircleCosts} 也不行：已核对 1.8 字节码
 *       （{@code GreatCircleDistanceCalculator}）它把 {@code getX()} 当经度、{@code getY()} 当纬度，
 *       与本项目 {@code lat, lon} 的约定<b>相反</b>（实测 “Bruchsal→Karlsruhe” 会被算成 11.2 km，
 *       而真值 19.3 km）—— 静默算错比直接报错更危险，故自行实现并统一约定为
 *       {@code Location.newInstance(纬度, 经度)}。</li>
 * </ul>
 *
 * <p>行驶时间按恒定平均车速折算（默认 {@value #DEFAULT_SPEED_KMH} km/h，返回<b>小时</b>）。
 * 本演示不做时窗约束，时间只用于给出到达时刻。
 */
public class HaversineCostModel extends AbstractForwardVehicleRoutingTransportCosts {

    /** 默认平均车速（km/h）：城市间干线取 60 更保守，避免 ETA 过于乐观。 */
    public static final double DEFAULT_SPEED_KMH = 60.0;

    private final double speedKmh;

    public HaversineCostModel() {
        this(DEFAULT_SPEED_KMH);
    }

    public HaversineCostModel(double speedKmh) {
        if (!(speedKmh > 0) || !Double.isFinite(speedKmh)) {
            throw new IllegalArgumentException("平均车速必须是正的有限值（km/h），实际: " + speedKmh);
        }
        this.speedKmh = speedKmh;
    }

    public double getSpeedKmh() {
        return speedKmh;
    }

    @Override
    public double getDistance(Location from, Location to, double departureTime, Vehicle vehicle) {
        return kmBetween(from, to);
    }

    @Override
    public double getTransportCost(Location from, Location to, double departureTime, Driver driver, Vehicle vehicle) {
        return kmBetween(from, to);
    }

    @Override
    public double getTransportTime(Location from, Location to, double departureTime, Driver driver, Vehicle vehicle) {
        return kmBetween(from, to) / speedKmh;
    }

    private static double kmBetween(Location from, Location to) {
        return GeoDistance.km(lat(from), lon(from), lat(to), lon(to));
    }

    /**
     * 纬度：约定 {@code x} 为纬度。
     *
     * <p>缺坐标时直接抛异常而不是回落 0：0/0 是几内亚湾的合法坐标，
     * 静默回落会把“数据缺失”伪装成一条真实的离谱路线。
     */
    private static double lat(Location location) {
        requireCoordinate(location);
        return location.getCoordinate().getX();
    }

    private static double lon(Location location) {
        requireCoordinate(location);
        return location.getCoordinate().getY();
    }

    private static void requireCoordinate(Location location) {
        if (location == null || location.getCoordinate() == null) {
            String id = location == null ? "null" : location.getId();
            throw new IllegalStateException("位置缺少经纬度坐标（需用 Location.newInstance(纬度, 经度) 创建）: " + id);
        }
    }
}
