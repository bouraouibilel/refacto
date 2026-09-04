package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.FindingStatus;
import com.refacto.migration.core.enums.Severity;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(
        String id,
        String projectId,
        String analysisId,
        String moduleName,
        String recipeId,
        String recipeVersion,
        Category category,
        Severity severity,
        int confidence, // 0 to 100
        AutomationLevel automationLevel,
        String filePath,
        int startLine,
        int endLine,
        String symbol,
        String description,
        String technicalReason,
        String migrationImpact,
        String suggestedAction,
        double estimatedEffortHours,
        String regressionRisk,
        FindingStatus status,
        String codeSnippet,
        String detectedCallPath,
        Long potentialRoundTrips
) {
    public static Finding of(
            String id,
            String moduleName,
            String recipeId,
            String recipeVersion,
            Category category,
            Severity severity,
            int confidence,
            AutomationLevel automationLevel,
            String filePath,
            int startLine,
            int endLine,
            String symbol,
            String description,
            String technicalReason,
            String migrationImpact,
            String suggestedAction,
            double estimatedEffortHours,
            String regressionRisk,
            String codeSnippet
    ) {
        return new Finding(
                id,
                null,
                null,
                moduleName,
                recipeId,
                recipeVersion,
                category,
                severity,
                confidence,
                automationLevel,
                filePath,
                startLine,
                endLine,
                symbol,
                description,
                technicalReason,
                migrationImpact,
                suggestedAction,
                estimatedEffortHours,
                regressionRisk,
                FindingStatus.OPEN,
                codeSnippet,
                null,
                null
        );
    }

    public String getRecipeClassName() {
        return Recipe.computeRecipeClassName(recipeId, null);
    }
}
