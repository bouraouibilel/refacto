package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.RecipeStatus;
import com.refacto.migration.core.enums.Severity;

import java.util.List;

public record Recipe(
        String id,
        String version,
        String name,
        String description,
        Category category,
        Severity defaultSeverity,
        String detectorEngine, // e.g., "AST", "SQL", "OPENREWRITE", "MAVEN"
        AutomationLevel automationLevel,
        List<String> dependencies, // Depends on other recipe IDs
        List<String> validations, // e.g. ["compilation", "unit-tests"]
        List<String> tags,
        RecipeStatus status,
        String author,
        String rationale
) {}
