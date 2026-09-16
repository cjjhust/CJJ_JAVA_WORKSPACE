package com.aslp.route.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GeoDistance} 单元测试。
 *
 * <p>用真实城市间距离做基准，把「第一个参数是纬度、第二个是经度」这一约定钉死：
 * 约定一旦写反，本类会直接失败，而不是等到生产环境把里程算错。
 *
 * <p>基准值以 IUGG 平均地球半径 6371.0088 km 计算（与实现同参数、独立算出），
 * 容差取 0.01 km —— 只容忍浮点误差，不容忍公式/常数/参数顺序的变化。
 */
class GeoDistanceTest {

    private static final double TOLERANCE_KM = 0.01;

    // 真实坐标（纬度, 经度）
    private static final double BRUCHSAL_LAT = 49.1243, BRUCHSAL_LON = 8.5987;
    private static final double KARLSRUHE_LAT = 49.0069, KARLSRUHE_LON = 8.4037;
    private static final double MOENCHENGLADBACH_LAT = 51.1805, MOENCHENGLADBACH_LON = 6.4428;
    private static final double DUESSELDORF_LAT = 51.2277, DUESSELDORF_LON = 6.7735;

    @Test
    @DisplayName("总仓到分仓的距离与真实值一致（Bruchsal → Karlsruhe ≈ 19.29 km）")
    void calculatesKnownDistanceBruchsalToKarlsruhe() {
        double km = GeoDistance.km(BRUCHSAL_LAT, BRUCHSAL_LON, KARLSRUHE_LAT, KARLSRUHE_LON);

        assertEquals(19.2936, km, TOLERANCE_KM);
    }

    @Test
    @DisplayName("跨德国长距离正确（Bruchsal → Mönchengladbach ≈ 275.42 km）")
    void calculatesLongDistanceAcrossGermany() {
        double km = GeoDistance.km(BRUCHSAL_LAT, BRUCHSAL_LON, MOENCHENGLADBACH_LAT, MOENCHENGLADBACH_LON);

        assertEquals(275.4177, km, TOLERANCE_KM);
    }

    @Test
    @DisplayName("1 度纬度 ≈ 111.2 km；49.11°N 处 1 度经度 ≈ 72.8 km（证明是球面而非平面）")
    void oneDegreeOfLatitudeAndLongitude() {
        assertEquals(111.1951, GeoDistance.km(0, 0, 1, 0), TOLERANCE_KM);
        assertEquals(72.7888, GeoDistance.km(49.11, 8.60, 49.11, 9.60), TOLERANCE_KM);
    }

    @Test
    @DisplayName("同点距离为 0，且距离对称")
    void zeroAndSymmetric() {
        assertEquals(0.0, GeoDistance.km(BRUCHSAL_LAT, BRUCHSAL_LON, BRUCHSAL_LAT, BRUCHSAL_LON), 1e-9);

        double forward = GeoDistance.km(BRUCHSAL_LAT, BRUCHSAL_LON, DUESSELDORF_LAT, DUESSELDORF_LON);
        double backward = GeoDistance.km(DUESSELDORF_LAT, DUESSELDORF_LON, BRUCHSAL_LAT, BRUCHSAL_LON);
        assertEquals(forward, backward, 1e-9);
        assertTrue(forward > 0);
    }

    @Test
    @DisplayName("对跖点不会因浮点误差让 asin 越界（返回半个地球周长）")
    void antipodalPointsDoNotOverflow() {
        double halfCircumference = Math.PI * GeoDistance.EARTH_RADIUS_KM;

        double km = GeoDistance.km(0, 0, 0, 180);
        assertEquals(halfCircumference, km, 0.5);
        assertTrue(Double.isFinite(km), "对跖点必须返回有限值，不能是 NaN");
    }

    @Test
    @DisplayName("roundKm 保留 1 位小数、round2 保留 2 位小数（回执里不出现 615.0999999）")
    void roundingHelpers() {
        assertEquals(615.1, GeoDistance.roundKm(615.0899), 1e-9);
        assertEquals(38.6, GeoDistance.roundKm(38.5872), 1e-9);
        assertEquals(9.93, GeoDistance.round2(9.9312), 1e-9);
    }
}
