package com.refacto.migration.recipe;

import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.MigrationWaveType;
import com.refacto.migration.core.enums.ValidationStatus;
import com.refacto.migration.core.model.MigrationWave;
import com.refacto.migration.core.model.Recipe;
import com.refacto.migration.core.model.RecipeExecution;

import java.util.*;

/**
 * Calculateur de graphe orienté acyclique (DAG) de dépendances entre recipes (Section 21)
 * et séquenceur de vagues de migration (Section 37).
 */
public class RecipeDagPlanner {

    public static class CircularDependencyException extends RuntimeException {
        public CircularDependencyException(String message) {
            super(message);
        }
    }

    /**
     * Calcule l'ordre d'exécution topologique des recipes en respectant les dépendances (dependsOn).
     * Lève une CircularDependencyException en cas de cycle.
     */
    public List<Recipe> computeExecutionOrder(Collection<Recipe> recipes) {
        Map<String, Recipe> recipeMap = new HashMap<>();
        Map<String, Set<String>> graph = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (Recipe r : recipes) {
            recipeMap.put(r.id(), r);
            graph.putIfAbsent(r.id(), new HashSet<>());
            inDegree.put(r.id(), 0);
        }

        // Build directed graph: dependency -> dependent
        for (Recipe r : recipes) {
            for (String depId : r.dependencies()) {
                if (recipeMap.containsKey(depId)) {
                    graph.computeIfAbsent(depId, k -> new HashSet<>()).add(r.id());
                    inDegree.put(r.id(), inDegree.get(r.id()) + 1);
                }
            }
        }

        // Kahn's algorithm
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<Recipe> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            ordered.add(recipeMap.get(currentId));

            for (String neighbor : graph.getOrDefault(currentId, Collections.emptySet())) {
                inDegree.put(neighbor, inDegree.get(neighbor) - 1);
                if (inDegree.get(neighbor) == 0) {
                    queue.add(neighbor);
                }
            }
        }

        if (ordered.size() != recipes.size()) {
            throw new CircularDependencyException("Dépendance circulaire détectée dans le graphe de recipes !");
        }

        return ordered;
    }

    /**
     * Répartit les recipes en vagues ordonnées (Wave 0 à Wave 7) selon la section 37.
     */
    public List<MigrationWave> buildMigrationWaves(Collection<Recipe> recipes) {
        List<Recipe> topologicallyOrdered = computeExecutionOrder(recipes);

        Map<MigrationWaveType, List<RecipeExecution>> waveExecutions = new LinkedHashMap<>();
        for (MigrationWaveType type : MigrationWaveType.values()) {
            waveExecutions.put(type, new ArrayList<>());
        }

        for (Recipe recipe : topologicallyOrdered) {
            MigrationWaveType targetWave = mapToWaveType(recipe);
            RecipeExecution exec = new RecipeExecution(
                    recipe.id(),
                    recipe.version(),
                    "all-modules",
                    Collections.emptyList(),
                    ValidationStatus.NOT_RUN,
                    0,
                    Collections.emptyList()
            );
            waveExecutions.get(targetWave).add(exec);
        }

        List<MigrationWave> result = new ArrayList<>();
        for (MigrationWaveType type : MigrationWaveType.values()) {
            List<RecipeExecution> execs = waveExecutions.get(type);
            // Always include baseline and final validation, or waves with recipes
            if (!execs.isEmpty() || type == MigrationWaveType.WAVE_0_BASELINE || type == MigrationWaveType.WAVE_7_FINAL_VALIDATION) {
                result.add(new MigrationWave(
                        type.getOrder(),
                        type,
                        type.name(),
                        type.getDescription(),
                        execs,
                        ValidationStatus.NOT_RUN
                ));
            }
        }

        return result;
    }

    private MigrationWaveType mapToWaveType(Recipe recipe) {
        Category cat = recipe.category();
        return switch (cat) {
            case JAVA17 -> MigrationWaveType.WAVE_1_JAVA;
            case DEPENDENCY -> MigrationWaveType.WAVE_2_BUILD_DEPS;
            case FRAMEWORK -> MigrationWaveType.WAVE_3_FRAMEWORKS;
            case DATABASE -> MigrationWaveType.WAVE_4_DB_OPTIM;
            case CODE -> MigrationWaveType.WAVE_5_CODE_MODERN;
            case ARCHITECTURE -> MigrationWaveType.WAVE_6_ARCH_DECOUPLING;
            default -> MigrationWaveType.WAVE_5_CODE_MODERN;
        };
    }
}
