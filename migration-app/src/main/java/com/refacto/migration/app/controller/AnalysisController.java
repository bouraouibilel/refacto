package com.refacto.migration.app.controller;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/analyses")
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final MigrationOrchestratorService orchestrator;

    public AnalysisController(MigrationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/latest")
    public ResponseEntity<MigrationOrchestratorService.AnalysisContext> getLatestAnalysis() {
        return orchestrator.getLatestAnalysis()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MigrationOrchestratorService.AnalysisContext> getAnalysis(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/modules")
    public ResponseEntity<List<ModuleDescriptor>> getModules(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> ResponseEntity.ok(ctx.modules()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/batches")
    public ResponseEntity<List<BatchDescriptor>> getBatches(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> ResponseEntity.ok(ctx.batches()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/dependencies")
    public ResponseEntity<List<Dependency>> getDependencies(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> ResponseEntity.ok(ctx.dependencies()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/findings")
    public ResponseEntity<List<Finding>> getFindings(
            @PathVariable String id,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String severity
    ) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> {
                    List<Finding> list = ctx.findings();
                    if (category != null) {
                        list = list.stream().filter(f -> f.category().name().equalsIgnoreCase(category)).toList();
                    }
                    if (severity != null) {
                        list = list.stream().filter(f -> f.severity().name().equalsIgnoreCase(severity)).toList();
                    }
                    return ResponseEntity.ok(list);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/architecture")
    public ResponseEntity<List<ArchitectureCoupling>> getArchitectureCouplings(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> ResponseEntity.ok(ctx.architectureCouplings()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/waves")
    public ResponseEntity<List<MigrationWave>> getWaves(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> ResponseEntity.ok(ctx.waves()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/dry-run")
    public ResponseEntity<DryRunResult> getDryRun(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> ResponseEntity.ok(ctx.dryRunResult()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/ddd-plan")
    public ResponseEntity<com.refacto.migration.core.model.DddRefactoringPlan> getDddPlan(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> ResponseEntity.ok(ctx.dddPlan()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/diffs/{diffId}/approve")
    public ResponseEntity<DiffEntry> approveDiff(@PathVariable String id, @PathVariable String diffId) {
        try {
            return ResponseEntity.ok(orchestrator.approveDiff(id, diffId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    public record RejectRequest(String reason) {}

    @PostMapping("/{id}/diffs/{diffId}/reject")
    public ResponseEntity<DiffEntry> rejectDiff(@PathVariable String id, @PathVariable String diffId, @RequestBody RejectRequest request) {
        try {
            return ResponseEntity.ok(orchestrator.rejectDiff(id, diffId, request.reason()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/apply")
    public ResponseEntity<List<DiffEntry>> applyTransformations(
            @PathVariable String id,
            @RequestParam(defaultValue = "true") boolean applyAutoSafe
    ) {
        try {
            return ResponseEntity.ok(orchestrator.applyTransformations(id, applyAutoSafe));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/validate")
    public ResponseEntity<ValidationResult> validateBuild(
            @PathVariable String id,
            @RequestParam(defaultValue = "true") boolean runTests
    ) {
        try {
            return ResponseEntity.ok(orchestrator.validateBuild(id, runTests));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<MigrationReport> getReport(@PathVariable String id) {
        try {
            return ResponseEntity.ok(orchestrator.generateReport(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/report/html")
    public ResponseEntity<String> getReportHtml(@PathVariable String id) {
        try {
            MigrationReport report = orchestrator.generateReport(id);
            String html = orchestrator.getReportingService().generateHtmlReport(report);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_HTML_VALUE)
                    .body(html);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/report/markdown")
    public ResponseEntity<String> getReportMarkdown(@PathVariable String id) {
        try {
            MigrationReport report = orchestrator.generateReport(id);
            String md = orchestrator.getReportingService().generateMarkdownReport(report);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .body(md);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping(value = "/{id}/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MigrationOrchestratorService.AnalysisContext> exportAnalysis(@PathVariable String id) {
        return orchestrator.getAnalysis(id)
                .map(ctx -> {
                    String projectName = ctx.project() != null ? ctx.project().name().replaceAll("[^a-zA-Z0-9.-]", "_") : "project";
                    String filename = "analysis-" + id + "-" + projectName + ".refacto.json";
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                            .body(ctx);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MigrationOrchestratorService.AnalysisContext> importAnalysisJson(
            @RequestBody MigrationOrchestratorService.AnalysisContext context
    ) {
        try {
            return ResponseEntity.ok(orchestrator.importAnalysis(context));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping(value = "/import/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MigrationOrchestratorService.AnalysisContext> importAnalysisFile(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file
    ) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            MigrationOrchestratorService.AnalysisContext ctx = mapper.readValue(
                    file.getInputStream(),
                    MigrationOrchestratorService.AnalysisContext.class
            );
            return ResponseEntity.ok(orchestrator.importAnalysis(ctx));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
