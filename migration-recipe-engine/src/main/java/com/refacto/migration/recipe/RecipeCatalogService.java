package com.refacto.migration.recipe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.refacto.migration.core.model.Recipe;
import com.refacto.migration.core.model.RecipePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Service de chargement et de gestion du catalogue de Recipes YAML (Sections 18, 19, 20, 78).
 */
public class RecipeCatalogService {

    private static final Logger log = LoggerFactory.getLogger(RecipeCatalogService.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final Map<String, Recipe> recipesById = new LinkedHashMap<>();
    private final Map<String, RecipePack> packsById = new LinkedHashMap<>();

    public RecipeCatalogService() {
        loadDefaultPacks();
    }

    public void registerRecipe(Recipe recipe) {
        recipesById.put(recipe.id(), recipe);
    }

    public Optional<Recipe> getRecipe(String id) {
        return Optional.ofNullable(recipesById.get(id));
    }

    public Collection<Recipe> getAllRecipes() {
        return Collections.unmodifiableCollection(recipesById.values());
    }

    public Collection<RecipePack> getAllPacks() {
        return Collections.unmodifiableCollection(packsById.values());
    }

    public Optional<RecipePack> getPack(String packId) {
        return Optional.ofNullable(packsById.get(packId));
    }

    private void loadDefaultPacks() {
        try {
            loadPackFromResource("/recipes/java17-pack.yaml");
            loadPackFromResource("/recipes/app-db-pack.yaml");
            loadPackFromResource("/recipes/app-code-pack.yaml");
            loadPackFromResource("/recipes/app-arch-pack.yaml");
            loadPackFromResource("/recipes/frameworks-pack.yaml");
        } catch (Exception e) {
            log.warn("Erreur chargement packs par défaut : {}", e.getMessage());
        }
    }

    public void loadPackFromResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.debug("Ressource introuvable : {}", resourcePath);
                return;
            }
            Map<String, Object> data = yamlMapper.readValue(is, new TypeReference<>() {});
            String packId = (String) data.get("id");
            String packName = (String) data.get("name");
            String description = (String) data.get("description");
            String version = (String) data.get("version");

            List<Map<String, Object>> recipeMaps = (List<Map<String, Object>>) data.get("recipes");
            List<String> recipeIds = new ArrayList<>();

            if (recipeMaps != null) {
                for (Map<String, Object> rMap : recipeMaps) {
                    Recipe recipe = parseRecipeMap(rMap);
                    registerRecipe(recipe);
                    recipeIds.add(recipe.id());
                }
            }

            RecipePack pack = new RecipePack(packId, packName, description, version, recipeIds, Collections.emptyList());
            packsById.put(packId, pack);
        }
    }

    private Recipe parseRecipeMap(Map<String, Object> map) {
        String id = (String) map.get("id");
        String version = (String) map.getOrDefault("version", "1.0");
        String name = (String) map.get("name");
        String desc = (String) map.get("description");
        String categoryStr = (String) map.get("category");
        String severityStr = (String) map.getOrDefault("severity", "MEDIUM");
        String engine = (String) map.getOrDefault("detectorEngine", "AST");
        String autoStr = (String) map.getOrDefault("automationLevel", "REVIEW_REQUIRED");
        List<String> deps = (List<String>) map.getOrDefault("dependsOn", Collections.emptyList());
        List<String> validations = (List<String>) map.getOrDefault("validation", List.of("compilation", "unit-tests"));
        List<String> tags = (List<String>) map.getOrDefault("tags", Collections.emptyList());
        String statusStr = (String) map.getOrDefault("status", "APPROVED");
        String author = (String) map.getOrDefault("author", "Migration Team");
        String rationale = (String) map.getOrDefault("rationale", "");
        String recipeClassName = (String) map.getOrDefault("recipeClassName", Recipe.computeRecipeClassName(id, name));

        return new Recipe(
                id,
                version,
                name,
                desc,
                com.refacto.migration.core.enums.Category.valueOf(categoryStr.toUpperCase()),
                com.refacto.migration.core.enums.Severity.valueOf(severityStr.toUpperCase()),
                engine,
                com.refacto.migration.core.enums.AutomationLevel.valueOf(autoStr.toUpperCase()),
                deps,
                validations,
                tags,
                com.refacto.migration.core.enums.RecipeStatus.valueOf(statusStr.toUpperCase()),
                author,
                rationale,
                recipeClassName
        );
    }
}
