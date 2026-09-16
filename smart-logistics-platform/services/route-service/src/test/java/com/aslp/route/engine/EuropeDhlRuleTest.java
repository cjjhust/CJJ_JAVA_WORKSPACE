package com.aslp.route.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 运费规则测试：欧洲 DHL 计费 = 基础费 + 距离×单价 + 重量×系数。
 *
 * <p>公式常量来自 {@link EuropeDhlRule}，此处按「契约」断言而非复述实现：
 * 基础费区分德国本土（5.0）与其他欧洲区（8.0）。
 */
class EuropeDhlRuleTest {

    /** 浮点比较容差（0.12 等十进制常量在二进制下无法精确表示）。 */
    private static final double DELTA = 1e-9;

    private static final double DISTANCE_KM = 100.0;
    private static final double WEIGHT_KG = 2.0;
    /** 100km × 0.12 = 12.0；2kg × 0.35 = 0.7 */
    private static final double VARIABLE_PART = 12.7;

    private final EuropeDhlRule rule = new EuropeDhlRule();

    @Test
    @DisplayName("德国本土（DE）：基础费 5.0")
    void deZoneUsesDomesticBaseFee() {
        assertEquals(5.0 + VARIABLE_PART, rule.calculate(DISTANCE_KM, WEIGHT_KG, "DE"), DELTA);
    }

    @Test
    @DisplayName("其他欧洲区：基础费 8.0")
    void nonDeZoneUsesInternationalBaseFee() {
        assertEquals(8.0 + VARIABLE_PART, rule.calculate(DISTANCE_KM, WEIGHT_KG, "FR"), DELTA);
    }

    @Test
    @DisplayName("零距离零重量：只剩基础费（边界下限）")
    void zeroDistanceAndWeightYieldsBaseFeeOnly() {
        assertEquals(5.0, rule.calculate(0.0, 0.0, "DE"), DELTA);
        assertEquals(8.0, rule.calculate(0.0, 0.0, "NL"), DELTA);
    }

    @Test
    @DisplayName("计费随距离与重量单调递增（同一区域内）")
    void feeIncreasesWithDistanceAndWeight() {
        double light = rule.calculate(50.0, 1.0, "DE");
        double heavy = rule.calculate(50.0, 10.0, "DE");
        double far = rule.calculate(500.0, 1.0, "DE");

        assertTrue(heavy > light, "重量越大运费越高");
        assertTrue(far > light, "距离越远运费越高");
    }

    @Test
    @DisplayName("区域编码大小写敏感：小写 de 视为其他欧洲区（记录现有行为）")
    void zoneCodeIsCaseSensitive() {
        double lowerCase = rule.calculate(0.0, 0.0, "de");
        double upperCase = rule.calculate(0.0, 0.0, "DE");

        assertEquals(8.0, lowerCase, DELTA);
        assertTrue(lowerCase > upperCase);
    }
}
