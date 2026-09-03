package com.refacto.migration.core.model;

import java.util.List;

public record RiskAssessment(
        int score, // 0 to 100
        String level, // LOW, MEDIUM, HIGH, CRITICAL
        List<RiskFactor> factors,
        String summary
) {
    public record RiskFactor(
            String category,
            String description,
            int weight,
            int scoreContribution,
            String impactDetails
    ) {}
}
