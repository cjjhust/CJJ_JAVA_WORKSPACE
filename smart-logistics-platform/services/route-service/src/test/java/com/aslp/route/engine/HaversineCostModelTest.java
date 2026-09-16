package com.aslp.route.engine;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.util.Coordinate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HaversineCostModel} 单元测试。
 *
 * <p>锁定三件事：
 * <ol>
 *   <li><b>单位是 km</b>（这是缺陷 #27 的第二层问题：jsprit 默认欧氏距离在经纬度上单位是「度」）；</li>
 *   <li><b>坐标顺序是 (纬度, 经度)</b> —— 与 jsprit 内置 {@code GreatCircleCosts} 相反，
 *       写反会让 Bruchsal→Karlsruhe 从 19.3 km 变成 11.2 km；</li>
 *   <li>缺坐标时<b>抛异常</b>而不是静默按 0/0 计算。</li>
 * </ol>
 */
class HaversineCostModelTest {

    private static final double TOLERANCE = 0.01;

    private final HaversineCostModel model = new HaversineCostModel();

    @Test
    @DisplayName("距离按真实大圆距离计算（Bruchsal → Karlsruhe = 19.29 km，而不是 0.19「度」）")
    void distanceIsHaversineKilometers() {
        double km = model.getDistance(bruchsal(), karlsruhe(), 0.0, null);

        assertEquals(19.2936, km, TOLERANCE);
    }

    @Test
    @DisplayName("坐标顺序为 (纬度, 经度)：与 jsprit 内置 GreatCircleCosts 的 (经度, 纬度) 相反")
    void coordinateOrderIsLatitudeThenLongitude() {
        // 若把 49.1243 当经度、8.5987 当纬度，jsprit 内置实现会算出 ≈11.18 km。
        // 本实现必须给出真实值 ≈19.29 km，两者差值足以区分约定。
        double km = model.getDistance(bruchsal(), karlsruhe(), 0.0, null);

        assertEquals(19.2936, km, TOLERANCE);
        assertTrue(km > 19.0 && km < 19.6, "疑似把经纬度顺序写反了，实际: " + km);
    }

    @Test
    @DisplayName("成本等于里程（都是 km），行驶时间 = 里程 / 车速（小时）")
    void costIsKilometersAndTimeIsHours() {
        double distance = model.getDistance(bruchsal(), karlsruhe(), 0.0, null);
        double cost = model.getTransportCost(bruchsal(), karlsruhe(), 0.0, null, null);
        double time = model.getTransportTime(bruchsal(), karlsruhe(), 0.0, null, null);

        assertEquals(distance, cost, 1e-9);
        assertEquals(19.2936 / 60.0, time, TOLERANCE / 60.0);
        assertEquals(0.3216, time, 0.001);
    }

    @Test
    @DisplayName("车速可配置：车速翻倍则行驶时间减半，里程不变")
    void speedOnlyAffectsTravelTime() {
        HaversineCostModel fast = new HaversineCostModel(120.0);

        assertEquals(model.getDistance(bruchsal(), karlsruhe(), 0.0, null),
                fast.getDistance(bruchsal(), karlsruhe(), 0.0, null), 1e-9);
        assertEquals(model.getTransportTime(bruchsal(), karlsruhe(), 0.0, null, null) / 2,
                fast.getTransportTime(bruchsal(), karlsruhe(), 0.0, null, null), 1e-9);
        assertEquals(120.0, fast.getSpeedKmh(), 1e-9);
        assertEquals(HaversineCostModel.DEFAULT_SPEED_KMH, model.getSpeedKmh(), 1e-9);
    }

    @Test
    @DisplayName("非法车速直接拒绝（0 / 负数 / NaN 都不允许）")
    void rejectsInvalidSpeed() {
        assertThrows(IllegalArgumentException.class, () -> new HaversineCostModel(0));
        assertThrows(IllegalArgumentException.class, () -> new HaversineCostModel(-60));
        assertThrows(IllegalArgumentException.class, () -> new HaversineCostModel(Double.NaN));
    }

    @Test
    @DisplayName("位置缺经纬度时抛异常，而不是回落 (0,0) —— 否则数据缺失会伪装成一条真实路线")
    void rejectsLocationWithoutCoordinate() {
        Location noCoordinate = Location.Builder.newInstance().setId("无坐标作业").build();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> model.getDistance(bruchsal(), noCoordinate, 0.0, null));
        assertTrue(ex.getMessage().contains("无坐标作业"), "异常消息应带上出错的作业 id：" + ex.getMessage());
    }

    /** 约定：{@code Coordinate.newInstance(纬度, 经度)}。 */
    private static Location bruchsal() {
        return location("Bruchsal-总仓", 49.1243, 8.5987);
    }

    private static Location karlsruhe() {
        return location("Karlsruhe", 49.0069, 8.4037);
    }

    private static Location location(String id, double lat, double lon) {
        return Location.Builder.newInstance()
                .setId(id)
                .setCoordinate(Coordinate.newInstance(lat, lon))
                .build();
    }
}
