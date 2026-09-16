package com.aslp.route.engine;

public class EuropeDhlRule implements FreightRule {
    @Override
    public double calculate(double distanceKm, double weightKg, String zone) {
        // 欧洲 DHL 运费规则：基础费 + 距离 * 单价 + 重量 * 系数
        double base = zone.equals("DE") ? 5.0 : 8.0; // 德国/其他欧洲
        return base + distanceKm * 0.12 + weightKg * 0.35;
    }
}
