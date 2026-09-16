package com.aslp.route.engine;

/**
 * 经纬度大圆距离（Haversine 公式）。
 *
 * <p><b>坐标约定：第一个参数是纬度、第二个参数是经度</b>
 * （与 {@code dto} 层 {@code lat} / {@code lon} 字段顺序、以及人的阅读习惯一致）。
 *
 * <p>⚠️ <b>不要</b>把本工具与 jsprit 内置的 {@code GreatCircleCosts} / {@code CrowFlyCosts} 混用：
 * 已核对 jsprit 1.8 的字节码（{@code GreatCircleDistanceCalculator.calculateDistance}），
 * 内置实现把 {@code Coordinate.getX()} 当<b>经度</b>、{@code getY()} 当<b>纬度</b>，
 * 即它期望 {@code Location.newInstance(经度, 纬度)}。两者的参数顺序<b>相反</b>，
 * 混用会让距离被静默算错（本次修复 VRP 引擎时即由该差异发现，
 * 详见 {@code readme.md} §9 缺陷 #27）。
 */
public final class GeoDistance {

    /** IUGG 平均地球半径（km）。 */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistance() {
    }

    /**
     * 计算两点间大圆距离（km）。
     *
     * @param lat1 起点纬度（-90 ~ 90）
     * @param lon1 起点经度（-180 ~ 180）
     * @param lat2 终点纬度（-90 ~ 90）
     * @param lon2 终点经度（-180 ~ 180）
     * @return 距离（km）
     */
    public static double km(double lat1, double lon1, double lat2, double lon2) {
        double halfDLat = Math.sin(Math.toRadians(lat2 - lat1) / 2);
        double halfDLon = Math.sin(Math.toRadians(lon2 - lon1) / 2);
        double h = halfDLat * halfDLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * halfDLon * halfDLon;
        // asin 的定义域是 [-1, 1]：浮点误差可能在近对跖点让 h 略微越界，故夹紧
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    /** 四舍五入到 1 位小数（0.1 km 精度足够物流展示，也让断言稳定）。 */
    public static double roundKm(double km) {
        return Math.round(km * 10.0) / 10.0;
    }

    /** 四舍五入到 2 位小数（用于小时数等展示值）。 */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
