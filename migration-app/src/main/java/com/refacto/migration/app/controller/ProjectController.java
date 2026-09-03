package com.refacto.migration.app.controller;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.Project;
import com.refacto.migration.core.model.TargetProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
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

    @PostMapping("/demo")
    public ResponseEntity<MigrationOrchestratorService.AnalysisContext> runDemoAnalysis() {
        Path samplePath = resolveDemoPath();
        Project project = orchestrator.registerProject("Sample Legacy APP", samplePath.toAbsolutePath().toString(), "main");
        MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(project.id(), TargetProfile.defaultJava17Profile());
        return ResponseEntity.ok(context);
    }

    private Path resolveDemoPath() {
        Path direct = java.nio.file.Paths.get("sample-legacy-app");
        if (java.nio.file.Files.exists(direct)) return direct;
        Path parent = java.nio.file.Paths.get("../sample-legacy-app");
        if (java.nio.file.Files.exists(parent)) return parent;
        Path abs = java.nio.file.Paths.get("d:/work/sample/refacto/sample-legacy-app");
        if (java.nio.file.Files.exists(abs)) return abs;
        return direct;
    }

    @PostMapping
    public ResponseEntity<Project> createProject(@RequestBody CreateProjectRequest request) {
        String cleanPath = request.repositoryPath() != null ? request.repositoryPath().trim() : "";
        if ((cleanPath.startsWith("\"") && cleanPath.endsWith("\"")) || (cleanPath.startsWith("'") && cleanPath.endsWith("'"))) {
            cleanPath = cleanPath.substring(1, cleanPath.length() - 1).trim();
        }

        Project project = orchestrator.registerProject(
                request.name(),
                cleanPath,
                request.branch() != null && !request.branch().isBlank() ? request.branch() : "main"
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
