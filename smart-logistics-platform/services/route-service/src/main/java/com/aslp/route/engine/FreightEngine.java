package com.aslp.route.engine;

public class FreightEngine {
    private final FreightRule rule;

    public FreightEngine(FreightRule rule) {
        this.rule = rule;
    }

    public double compute(double distanceKm, double weightKg, String zone) {
        return rule.calculate(distanceKm, weightKg, zone);
    }
}
