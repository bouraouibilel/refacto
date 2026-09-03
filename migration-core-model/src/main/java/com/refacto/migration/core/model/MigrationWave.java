package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.MigrationWaveType;
import com.refacto.migration.core.enums.ValidationStatus;
import java.util.List;

public record MigrationWave(
        int index,
        MigrationWaveType type,
        String name,
        String description,
        List<RecipeExecution> recipeExecutions,
        ValidationStatus status
) {}
