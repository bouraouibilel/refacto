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

    public record RunAnalysisRequest(
            String targetJavaVersion,
            String targetSpringBootVersion,
            String targetSpringBatchVersion,
            String id,
            String name,
            String description,
            java.util.Map<String, String> targets
    ) {
        public TargetProfile toTargetProfile() {
            if (targets != null && !targets.isEmpty()) {
                return new TargetProfile(
                        id != null ? id : "custom-target",
                        name != null ? name : "Cible personnalisée",
                        description != null ? description : "",
                        targetJavaVersion != null ? targetJavaVersion : targets.getOrDefault("java", "21"),
                        targetSpringBootVersion != null ? targetSpringBootVersion : targets.getOrDefault("spring-boot", "3.3.4"),
                        targetSpringBatchVersion != null ? targetSpringBatchVersion : targets.getOrDefault("spring-batch", "5.1.2"),
                        targets
                );
            }
            return TargetProfile.create(targetJavaVersion, targetSpringBootVersion, targetSpringBatchVersion);
        }
    }

    @PostMapping("/demo")
    public ResponseEntity<?> runDemoAnalysis(
            @RequestBody(required = false) RunAnalysisRequest request,
            @RequestParam(defaultValue = "false") boolean async
    ) {
        Path samplePath = resolveDemoPath();
        Project project = orchestrator.registerProject("Sample Legacy APP", samplePath.toAbsolutePath().toString(), "main");
        TargetProfile profile = request != null ? request.toTargetProfile() : TargetProfile.java21Profile();
        if (async) {
            return ResponseEntity.accepted().body(orchestrator.startAnalysisAsync(project.id(), profile));
        }
        MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(project.id(), profile);
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
    public ResponseEntity<?> runAnalysis(
            @PathVariable String id,
            @RequestBody(required = false) RunAnalysisRequest request,
            @RequestParam(defaultValue = "false") boolean async
    ) {
        TargetProfile profile = request != null ? request.toTargetProfile() : TargetProfile.java21Profile();
        if (async) {
            return ResponseEntity.accepted().body(orchestrator.startAnalysisAsync(id, profile));
        }
        MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(id, profile);
        return ResponseEntity.ok(context);
    }
}
