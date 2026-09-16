package com.aslp.route.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M3 运费引擎测试：{@link FreightEngine} 的职责是「选择并委托规则」，本身不含计费逻辑。
 *
 * <p>因此测试重点为：入参原样透传给规则、规则结果原样返回（后续接入多承运商时，
 * 规则选择逻辑会在此类内扩展，届时这些契约用例可继续生效）。
 */
class FreightEngineTest {

    private static final double DELTA = 1e-9;

    @Test
    @DisplayName("透传：距离 / 重量 / 区域原样传给规则，结果原样返回")
    void delegatesToRuleAndReturnsItsResult() {
        FreightRule rule = mock(FreightRule.class);
        when(rule.calculate(anyDouble(), anyDouble(), eq("DE"))).thenReturn(17.7);
        FreightEngine engine = new FreightEngine(rule);

        double fee = engine.compute(100.0, 2.0, "DE");

        assertEquals(17.7, fee, DELTA);
        verify(rule).calculate(100.0, 2.0, "DE");
    }

    @Test
    @DisplayName("装配 DHL 规则时与规则自身计算结果一致")
    void matchesRuleResultWhenBoundToDhlRule() {
        EuropeDhlRule dhlRule = new EuropeDhlRule();
        FreightEngine engine = new FreightEngine(dhlRule);

        assertEquals(dhlRule.calculate(100.0, 2.0, "DE"), engine.compute(100.0, 2.0, "DE"), DELTA);
    }

    @Test
    @DisplayName("同一实例可复用：多次调用结果一致（无状态）")
    void isStatelessAcrossCalls() {
        FreightEngine engine = new FreightEngine(new EuropeDhlRule());

        double first = engine.compute(10.0, 1.0, "DE");
        double second = engine.compute(10.0, 1.0, "DE");

        assertEquals(first, second, DELTA);
    }
}
