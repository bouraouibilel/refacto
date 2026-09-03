package com.refacto.migration.app.controller;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.Recipe;
import com.refacto.migration.core.model.RecipePack;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;

@RestController
@RequestMapping("/api/recipes")
@CrossOrigin(origins = "*")
public class RecipeController {

    private final MigrationOrchestratorService orchestrator;

    public RecipeController(MigrationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public ResponseEntity<Collection<Recipe>> listRecipes() {
        return ResponseEntity.ok(orchestrator.getRecipeCatalog().getAllRecipes());
    }

    @GetMapping("/packs")
    public ResponseEntity<Collection<RecipePack>> listPacks() {
        return ResponseEntity.ok(orchestrator.getRecipeCatalog().getAllPacks());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Recipe> getRecipe(@PathVariable String id) {
        return orchestrator.getRecipeCatalog().getRecipe(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
