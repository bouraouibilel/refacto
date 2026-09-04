package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.ChangeStatus;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DiffEntry(
        String id,
        String findingId,
        String recipeId,
        String recipeName,
        String filePath,
        AutomationLevel automationLevel,
        String originalContent,
        String transformedContent,
        String unifiedDiff,
        ChangeStatus status,
        String rejectionReason,
        String explanation
) {
    public static DiffEntry of(
            String id,
            String findingId,
            String recipeId,
            String recipeName,
            String filePath,
            AutomationLevel automationLevel,
            String originalContent,
            String transformedContent,
            String unifiedDiff,
            String explanation
    ) {
        return new DiffEntry(
                id,
                findingId,
                recipeId,
                recipeName,
                filePath,
                automationLevel,
                originalContent,
                transformedContent,
                unifiedDiff,
                ChangeStatus.PENDING,
                null,
                explanation
        );
    }

    public String getRecipeClassName() {
        return Recipe.computeRecipeClassName(recipeId, recipeName);
    }
}
