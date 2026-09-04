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
        String rationale,
        String recipeClassName
) {
    public Recipe(
            String id,
            String version,
            String name,
            String description,
            Category category,
            Severity defaultSeverity,
            String detectorEngine,
            AutomationLevel automationLevel,
            List<String> dependencies,
            List<String> validations,
            List<String> tags,
            RecipeStatus status,
            String author,
            String rationale
    ) {
        this(
                id, version, name, description, category, defaultSeverity,
                detectorEngine, automationLevel, dependencies, validations,
                tags, status, author, rationale,
                computeRecipeClassName(id, name)
        );
    }

    public static String computeRecipeClassName(String id, String name) {
        if (id == null) return "UnknownRecipe";
        return switch (id.toUpperCase().trim()) {
            case "APP-BATCH-001" -> "JobBuilderFactoryModernizationRecipe";
            case "APP-BATCH-002" -> "StepBuilderFactoryModernizationRecipe";
            case "APP-BATCH-003" -> "DataSourceBatch5MigrationRecipe";
            case "APP-BATCH-004" -> "BatchStepTransactionManagerRecipe";
            case "APP-DB-001" -> "SelectForUpdateWithoutSkipLockedRecipe";
            case "APP-DB-002" -> "NPlusOneLoopQueryRecipe";
            case "APP-DB-003" -> "DirtyReadIsolationLevelRecipe";
            case "APP-CODE-001" -> "GodClassSplitRecipe";
            case "APP-CODE-002" -> "LongMethodRefactoringRecipe";
            case "APP-ARCH-001" -> "CrossModuleCouplingIsolationRecipe";
            case "JAVA17-001" -> "TryWithResourcesModernizationRecipe";
            case "JAVA17-002" -> "FileToPathModernizationRecipe";
            case "JAVA17-003" -> "LoopToStreamModernizationRecipe";
            case "JAVA17-004" -> "SwitchExpressionModernizationRecipe";
            case "JAVA17-005" -> "AnonymousClassToLambdaRecipe";
            case "JAVA17-006" -> "LambdaToMethodReferenceRecipe";
            case "JAVA17-007" -> "NullSafetyOptionalJpaRecipe";
            case "FRAMEWORK-001" -> "SpringSecurity6FilterChainRecipe";
            case "FRAMEWORK-002" -> "Hibernate6CriteriaToJpaRecipe";
            case "FRAMEWORK-003" -> "JavaxToJakartaNamespaceRecipe";
            case "FRAMEWORK-004" -> "JUnit4ToJupiterMigrationRecipe";
            default -> {
                String clean = id.replaceAll("[^a-zA-Z0-9]", "");
                yield (clean.isEmpty() ? "Standard" : clean.substring(0, 1).toUpperCase() + clean.substring(1)) + "Recipe";
            }
        };
    }
}
