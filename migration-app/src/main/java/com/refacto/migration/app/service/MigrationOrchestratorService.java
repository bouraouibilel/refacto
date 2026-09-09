package com.refacto.migration.app.service;

import com.refacto.migration.ai.AiAssistantService;
import com.refacto.migration.code.ArchitectureAnalyzerService;
import com.refacto.migration.code.CodeAnalyzerService;
import com.refacto.migration.core.enums.ValidationStatus;
import com.refacto.migration.core.model.*;
import com.refacto.migration.dependency.DependencyAnalyzerService;
import com.refacto.migration.discovery.MavenProjectDiscoveryService;
import com.refacto.migration.discovery.SpringBatchDiscoveryService;
import com.refacto.migration.performance.PerformanceEngineService;
import com.refacto.migration.recipe.RecipeCatalogService;
import com.refacto.migration.recipe.RecipeDagPlanner;
import com.refacto.migration.recipe.RiskScoringService;
import com.refacto.migration.reporting.ReportingEngineService;
import com.refacto.migration.sql.SqlAnalyzerService;
import com.refacto.migration.transformation.DryRunService;
import com.refacto.migration.transformation.TransformationApplierService;
import com.refacto.migration.validation.BuildValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrateur central de la plateforme de migration (Section 7 : Workflow global).
 * Analyze -> Explain -> Plan -> Transform -> Validate.
 */
@Service
public class MigrationOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(MigrationOrchestratorService.class);

    private final MavenProjectDiscoveryService projectDiscovery = new MavenProjectDiscoveryService();
    private final SpringBatchDiscoveryService batchDiscovery = new SpringBatchDiscoveryService();
    private final DependencyAnalyzerService dependencyAnalyzer = new DependencyAnalyzerService();
    private final CodeAnalyzerService codeAnalyzer = new CodeAnalyzerService();
    private final ArchitectureAnalyzerService architectureAnalyzer = new ArchitectureAnalyzerService();
    private final SqlAnalyzerService sqlAnalyzer = new SqlAnalyzerService();
    private final RecipeCatalogService recipeCatalog = new RecipeCatalogService();
    private final RecipeDagPlanner dagPlanner = new RecipeDagPlanner();
    private final RiskScoringService riskScoring = new RiskScoringService();
    private final DryRunService dryRunService = new DryRunService();
    private final TransformationApplierService applierService = new TransformationApplierService();
    private final BuildValidationService validationService = new BuildValidationService();
    private final PerformanceEngineService performanceService = new PerformanceEngineService();
    private final ReportingEngineService reportingService = new ReportingEngineService();
    private final AiAssistantService aiAssistant = new AiAssistantService();

    // In-memory state store for analyses and campaigns
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<String, AnalysisContext> analyses = new ConcurrentHashMap<>();

    public record AnalysisContext(
            String analysisId,
            Project project,
            RepositorySnapshot snapshot,
            List<ModuleDescriptor> modules,
            List<BatchDescriptor> batches,
            List<Dependency> dependencies,
            TargetProfile targetProfile,
            List<Finding> findings,
            RiskAssessment riskAssessment,
            List<ArchitectureCoupling> architectureCouplings,
            List<MigrationWave> waves,
            DryRunResult dryRunResult,
            ValidationResult validationResult,
            List<PerformanceComparison> performanceComparisons
    ) {}

    public Project registerProject(String name, String localPathOrUri, String branch) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Project project = Project.create(id, name, localPathOrUri, branch, "HEAD");
        projects.put(id, project);
        return project;
    }

    public List<Project> listProjects() {
        return new ArrayList<>(projects.values());
    }

    public Optional<Project> getProject(String projectId) {
        return Optional.ofNullable(projects.get(projectId));
    }

    public AnalysisContext runFullAnalysis(String projectId, TargetProfile targetProfile) {
        Project project = projects.get(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Projet introuvable : " + projectId);
        }

        Path rootPath = Paths.get(project.repositoryUri());
        if (!Files.exists(rootPath)) {
            Path fallback = Paths.get("..").resolve(project.repositoryUri()).normalize();
            if (Files.exists(fallback)) {
                rootPath = fallback;
            } else {
                throw new IllegalArgumentException("Chemin du projet introuvable : " + project.repositoryUri() +
                        " (chemin absolu testé : " + rootPath.toAbsolutePath() + ")");
            }
        }
        log.info("Lancement de l'analyse complète pour {} sur {}", project.name(), rootPath);

        if (targetProfile == null) {
            targetProfile = TargetProfile.defaultJava17Profile();
        }

        // 1. Project Discovery
        RepositorySnapshot snapshot = projectDiscovery.discoverSnapshot(rootPath, project.branch(), project.currentCommit());
        List<ModuleDescriptor> modules = projectDiscovery.discoverModules(rootPath);

        // 2. Spring Batch Discovery
        List<BatchDescriptor> batches = new ArrayList<>();
        for (ModuleDescriptor mod : modules) {
            Path modDir = rootPath.resolve(mod.relativePath());
            batches.addAll(batchDiscovery.discoverBatches(rootPath, mod.artifactId(), modDir));
        }

        // 3. Dependency Analysis
        List<Dependency> dependencies = dependencyAnalyzer.buildInventory(modules, targetProfile);

        // 4. Code & SQL Analysis (Findings)
        List<Finding> findings = new ArrayList<>();
        for (ModuleDescriptor mod : modules) {
            Path modDir = rootPath.resolve(mod.relativePath());
            findings.addAll(codeAnalyzer.analyzeModule(rootPath, mod.artifactId(), modDir));
            findings.addAll(sqlAnalyzer.analyzeModule(rootPath, mod.artifactId(), modDir));
        }

        // 5. Architecture Analysis
        ArchitectureAnalyzerService.ArchitectureAnalysisResult archResult =
                architectureAnalyzer.analyzeArchitecture(rootPath, modules, batches);
        findings.addAll(archResult.findings());

        // 6. Risk Scoring
        RiskAssessment riskAssessment = riskScoring.assessRisk(modules, dependencies, findings);

        // 7. Migration Plan (Waves)
        List<MigrationWave> waves = dagPlanner.buildMigrationWaves(recipeCatalog.getAllRecipes());

        // 8. Dry Run
        int totalFiles = modules.stream().mapToInt(ModuleDescriptor::sourceCount).sum();
        DryRunResult dryRunResult = dryRunService.executeDryRun(rootPath, findings, totalFiles);

        String analysisId = UUID.randomUUID().toString().substring(0, 8);
        AnalysisContext context = new AnalysisContext(
                analysisId,
                project,
                snapshot,
                modules,
                batches,
                dependencies,
                targetProfile,
                findings,
                riskAssessment,
                archResult.couplings(),
                waves,
                dryRunResult,
                null,
                new ArrayList<>()
        );

        analyses.put(analysisId, context);
        log.info("Analyse {} terminée : {} modules, {} batchs, {} findings, risque {}",
                analysisId, modules.size(), batches.size(), findings.size(), riskAssessment.score());

        return context;
    }

    public Optional<AnalysisContext> getAnalysis(String analysisId) {
        return Optional.ofNullable(analyses.get(analysisId));
    }

    public Optional<AnalysisContext> getLatestAnalysis() {
        return analyses.values().stream().reduce((first, second) -> second);
    }

    public AnalysisContext importAnalysis(AnalysisContext importedCtx) {
        if (importedCtx == null) {
            throw new IllegalArgumentException("Le contexte d'analyse importé ne peut pas être null");
        }
        if (importedCtx.project() != null) {
            projects.put(importedCtx.project().id(), importedCtx.project());
        }
        analyses.put(importedCtx.analysisId(), importedCtx);
        log.info("Analyse {} importée avec succès (Projet: {})",
                importedCtx.analysisId(),
                importedCtx.project() != null ? importedCtx.project().name() : "N/A");
        return importedCtx;
    }

    public void addPerformanceComparison(String analysisId, PerformanceComparison comparison) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx != null) {
            ctx.performanceComparisons().add(comparison);
        }
    }

    public DiffEntry approveDiff(String analysisId, String diffId) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null || ctx.dryRunResult() == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }

        for (int i = 0; i < ctx.dryRunResult().diffEntries().size(); i++) {
            DiffEntry entry = ctx.dryRunResult().diffEntries().get(i);
            if (entry.id().equals(diffId)) {
                DiffEntry approved = applierService.approveChange(entry);
                ctx.dryRunResult().diffEntries().set(i, approved);
                return approved;
            }
        }
        throw new IllegalArgumentException("Diff introuvable : " + diffId);
    }

    public DiffEntry rejectDiff(String analysisId, String diffId, String reason) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null || ctx.dryRunResult() == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }

        for (int i = 0; i < ctx.dryRunResult().diffEntries().size(); i++) {
            DiffEntry entry = ctx.dryRunResult().diffEntries().get(i);
            if (entry.id().equals(diffId)) {
                DiffEntry rejected = applierService.rejectChange(entry, reason);
                ctx.dryRunResult().diffEntries().set(i, rejected);
                return rejected;
            }
        }
        throw new IllegalArgumentException("Diff introuvable : " + diffId);
    }

    public List<DiffEntry> applyTransformations(String analysisId, boolean applyAutoSafe) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null || ctx.dryRunResult() == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }

        Path rootPath = Paths.get(ctx.project().repositoryUri());
        List<DiffEntry> updated = applierService.applyApprovedChanges(rootPath, ctx.dryRunResult().diffEntries(), applyAutoSafe);
        return updated;
    }

    public ValidationResult validateBuild(String analysisId, boolean runTests) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }

        Path rootPath = Paths.get(ctx.project().repositoryUri());
        ValidationResult valResult = validationService.validateProject(rootPath, runTests);

        // Update context with validation result
        AnalysisContext updated = new AnalysisContext(
                ctx.analysisId(), ctx.project(), ctx.snapshot(), ctx.modules(), ctx.batches(),
                ctx.dependencies(), ctx.targetProfile(), ctx.findings(), ctx.riskAssessment(),
                ctx.architectureCouplings(), ctx.waves(), ctx.dryRunResult(), valResult, ctx.performanceComparisons()
        );
        analyses.put(analysisId, updated);
        return valResult;
    }

    public MigrationReport generateReport(String analysisId) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("Projet", ctx.project().name());
        summary.put("Branche", ctx.project().branch());
        summary.put("Nombre de modules", ctx.modules().size());
        summary.put("Traitements Batch", ctx.batches().size());
        summary.put("Dépendances à migrer", ctx.dependencies().stream().filter(d -> !"UP_TO_DATE".equals(d.migrationStatus())).count());
        summary.put("Score de Risque", ctx.riskAssessment().score() + "/100 (" + ctx.riskAssessment().level() + ")");
        summary.put("Findings totaux", ctx.findings().size());

        List<String> recommendations = new ArrayList<>();
        recommendations.add("Appliquer en priorité la vague 1 (Modernisation Java 17) après validation des tests unitaires.");
        recommendations.add("Corriger les accès directs à la base de données hors DAO (APP-DB-001) avant toute montée de version JPA/Hibernate.");
        recommendations.add("Étudier le déterminisme des SELECT FOR UPDATE (APP-DB-003) pour éviter les deadlocks en environnement concurrent.");
        recommendations.add("Substituer les boucles d'appels DAO (APP-DB-004) par des opérations par lot.");
        recommendations.add("Évaluer la bascule des traitements de masse JPA vers du SQL direct (APP-DB-002) avant de spécialiser les projections (APP-DB-005).");

        return new MigrationReport(
                ctx.project().id(),
                ctx.project().name(),
                Instant.now(),
                summary,
                ctx.snapshot(),
                ctx.modules(),
                ctx.batches(),
                ctx.dependencies(),
                ctx.targetProfile(),
                ctx.riskAssessment(),
                ctx.findings(),
                ctx.dryRunResult() != null ? ctx.dryRunResult().diffEntries().stream().filter(d -> d.status() == com.refacto.migration.core.enums.ChangeStatus.APPLIED).toList() : Collections.emptyList(),
                ctx.dryRunResult() != null ? ctx.dryRunResult().diffEntries().stream().filter(d -> d.status() == com.refacto.migration.core.enums.ChangeStatus.REJECTED).toList() : Collections.emptyList(),
                ctx.validationResult() != null ? ctx.validationResult() : ValidationResult.success("Validation non exécutée"),
                ctx.performanceComparisons(),
                ctx.architectureCouplings(),
                recommendations
        );
    }

    public RecipeCatalogService getRecipeCatalog() {
        return recipeCatalog;
    }

    public AiAssistantService getAiAssistant() {
        return aiAssistant;
    }

    public PerformanceEngineService getPerformanceService() {
        return performanceService;
    }

    public ReportingEngineService getReportingService() {
        return reportingService;
    }
}
