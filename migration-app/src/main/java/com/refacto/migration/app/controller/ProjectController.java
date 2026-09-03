package com.refacto.migration.app.controller;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.Project;
import com.refacto.migration.core.model.TargetProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin(origins = "*")
public class ProjectController {

    private final MigrationOrchestratorService orchestrator;

    public ProjectController(MigrationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    public record CreateProjectRequest(String name, String repositoryPath, String branch) {}

    @PostMapping
    public ResponseEntity<Project> createProject(@RequestBody CreateProjectRequest request) {
        Project project = orchestrator.registerProject(
                request.name(),
                request.repositoryPath(),
                request.branch() != null ? request.branch() : "main"
        );
        return ResponseEntity.ok(project);
    }

    @GetMapping
    public ResponseEntity<List<Project>> listProjects() {
        return ResponseEntity.ok(orchestrator.listProjects());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Project> getProject(@PathVariable String id) {
        return orchestrator.getProject(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/analyses")
    public ResponseEntity<MigrationOrchestratorService.AnalysisContext> runAnalysis(
            @PathVariable String id,
            @RequestBody(required = false) TargetProfile targetProfile
    ) {
        MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(id, targetProfile);
        return ResponseEntity.ok(context);
    }
}
