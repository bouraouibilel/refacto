package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.ValidationStatus;
import java.util.List;

public record RecipeExecution(
        String recipeId,
        String recipeVersion,
        String moduleName,
        List<String> filesModified,
        ValidationStatus status,
        long durationMs,
        List<DiffEntry> diffs
) {}
