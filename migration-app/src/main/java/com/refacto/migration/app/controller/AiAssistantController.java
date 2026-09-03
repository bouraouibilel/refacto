package com.refacto.migration.app.controller;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.Dependency;
import com.refacto.migration.core.model.DiffEntry;
import com.refacto.migration.core.model.Finding;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*")
public class AiAssistantController {

    private final MigrationOrchestratorService orchestrator;

    public AiAssistantController(MigrationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/explain-finding")
    public ResponseEntity<Map<String, String>> explainFinding(@RequestBody Finding finding) {
        String explanation = orchestrator.getAiAssistant().explainFinding(finding);
        return ResponseEntity.ok(Map.of("explanation", explanation));
    }

    @PostMapping("/explain-conflict")
    public ResponseEntity<Map<String, String>> explainConflict(@RequestBody Dependency dependency) {
        String explanation = orchestrator.getAiAssistant().explainDependencyConflict(dependency);
        return ResponseEntity.ok(Map.of("explanation", explanation));
    }

    @PostMapping("/explain-diff")
    public ResponseEntity<Map<String, String>> explainDiff(@RequestBody DiffEntry diff) {
        String explanation = orchestrator.getAiAssistant().explainDiff(diff);
        return ResponseEntity.ok(Map.of("explanation", explanation));
    }
}
