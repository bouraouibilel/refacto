package com.refacto.migration.app.controller;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.ModuleReorganizationPlan;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analyses/{id}/module-reorganization")
@CrossOrigin(origins = "*")
public class ModuleReorganizationController {

    private final MigrationOrchestratorService orchestrator;

    public ModuleReorganizationController(MigrationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public ResponseEntity<ModuleReorganizationPlan> getReorganizationPlan(@PathVariable String id) {
        try {
            return ResponseEntity.ok(orchestrator.getModuleReorganizationPlan(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/preview")
    public ResponseEntity<ModuleReorganizationPlan> previewReorganization(
            @PathVariable String id,
            @RequestBody ModuleReorganizationPlan customizedPlan
    ) {
        try {
            return ResponseEntity.ok(orchestrator.previewModuleReorganization(id, customizedPlan));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/apply")
    public ResponseEntity<ModuleReorganizationPlan> applyReorganization(
            @PathVariable String id,
            @RequestBody ModuleReorganizationPlan planToApply
    ) {
        try {
            return ResponseEntity.ok(orchestrator.applyModuleReorganization(id, planToApply));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
