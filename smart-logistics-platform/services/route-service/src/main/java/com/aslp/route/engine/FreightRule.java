package com.aslp.route.engine;

public interface FreightRule {
    double calculate(double distanceKm, double weightKg, String zone);
}
