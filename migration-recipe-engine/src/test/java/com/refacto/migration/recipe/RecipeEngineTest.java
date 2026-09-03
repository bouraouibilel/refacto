package com.refacto.migration.recipe;

import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.RecipeStatus;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.MigrationWave;
import com.refacto.migration.core.model.Recipe;
import com.refacto.migration.core.model.RiskAssessment;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeEngineTest {

    @Test
    void shouldLoadDefaultPacksFromYaml() {
        RecipeCatalogService catalog = new RecipeCatalogService();

        assertThat(catalog.getAllPacks()).isNotEmpty();
        assertThat(catalog.getRecipe("APP-DB-001")).isPresent();
        assertThat(catalog.getRecipe("APP-DB-002")).isPresent();
        assertThat(catalog.getRecipe("APP-DB-005")).isPresent();
        assertThat(catalog.getRecipe("JAVA17-001")).isPresent();

        Recipe appDb005 = catalog.getRecipe("APP-DB-005").get();
        assertThat(appDb005.dependencies()).contains("APP-DB-002");
    }

    @Test
    void shouldComputeTopologicalOrderAndRespectDependencies() {
        RecipeCatalogService catalog = new RecipeCatalogService();
        RecipeDagPlanner planner = new RecipeDagPlanner();

        Recipe r1 = catalog.getRecipe("APP-DB-001").orElseThrow();
        Recipe r2 = catalog.getRecipe("APP-DB-002").orElseThrow();
        Recipe r5 = catalog.getRecipe("APP-DB-005").orElseThrow();

        // Pass recipes in reverse order
        List<Recipe> ordered = planner.computeExecutionOrder(List.of(r5, r2, r1));

        int idxR1 = ordered.indexOf(r1);
        int idxR2 = ordered.indexOf(r2);
        int idxR5 = ordered.indexOf(r5);

        // APP-DB-001 must be before APP-DB-002, and APP-DB-002 must be before APP-DB-005!
        assertThat(idxR1).isLessThan(idxR2);
        assertThat(idxR2).isLessThan(idxR5);
    }

    @Test
    void shouldThrowOnCircularDependency() {
        RecipeDagPlanner planner = new RecipeDagPlanner();

        Recipe a = new Recipe("A", "1.0", "A", "A", Category.CODE, Severity.LOW, "AST", AutomationLevel.AUTO_SAFE, List.of("B"), List.of(), List.of(), RecipeStatus.APPROVED, "me", "");
        Recipe b = new Recipe("B", "1.0", "B", "B", Category.CODE, Severity.LOW, "AST", AutomationLevel.AUTO_SAFE, List.of("A"), List.of(), List.of(), RecipeStatus.APPROVED, "me", "");

        assertThatThrownBy(() -> planner.computeExecutionOrder(List.of(a, b)))
                .isInstanceOf(RecipeDagPlanner.CircularDependencyException.class)
                .hasMessageContaining("circulaire");
    }

    @Test
    void shouldBuildMigrationWaves() {
        RecipeCatalogService catalog = new RecipeCatalogService();
        RecipeDagPlanner planner = new RecipeDagPlanner();

        List<MigrationWave> waves = planner.buildMigrationWaves(catalog.getAllRecipes());

        assertThat(waves).isNotEmpty();
        assertThat(waves.get(0).type().name()).isEqualTo("WAVE_0_BASELINE");
    }
}
