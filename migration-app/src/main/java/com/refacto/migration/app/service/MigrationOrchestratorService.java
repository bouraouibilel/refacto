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
import com.refacto.migration.transformation.ModuleReorganizationService;
import com.refacto.migration.transformation.TransformationApplierService;
import com.refacto.migration.validation.BuildValidationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
    private final com.refacto.migration.code.DddRefactoringService dddRefactoringService = new com.refacto.migration.code.DddRefactoringService();
    private final com.refacto.migration.transformation.ModuleReorganizationService moduleReorganizationService = new com.refacto.migration.transformation.ModuleReorganizationService();

    // In-memory state store for analyses and campaigns
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<String, AnalysisContext> analyses = new ConcurrentHashMap<>();
    private final Map<String, AnalysisSummary> analysisSummaries = new ConcurrentHashMap<>();
    private final ExecutorService analysisExecutor = Executors.newCachedThreadPool();
    private final ObjectMapper jsonMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Path HISTORY_DIR = Paths.get(".refacto-history");

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
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
            List<PerformanceComparison> performanceComparisons,
            DddRefactoringPlan dddPlan,
            ModuleReorganizationPlan moduleReorganizationPlan
    ) {}

    @PostConstruct
    public void loadPersistedAnalyses() {
        try {
            if (Files.exists(HISTORY_DIR) && Files.isDirectory(HISTORY_DIR)) {
                try (var stream = Files.list(HISTORY_DIR)) {
                    for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                        try {
                            AnalysisContext ctx = jsonMapper.readValue(file.toFile(), AnalysisContext.class);
                            if (ctx != null && ctx.analysisId() != null) {
                                analyses.put(ctx.analysisId(), ctx);
                                if (ctx.project() != null) {
                                    projects.put(ctx.project().id(), ctx.project());
                                }
                                Instant fileTime = Files.getLastModifiedTime(file).toInstant();
                                AnalysisSummary summary = new AnalysisSummary(
                                        ctx.analysisId(),
                                        ctx.project() != null ? ctx.project().id() : "unknown",
                                        ctx.project() != null ? ctx.project().name() : "Projet",
                                        ctx.project() != null ? ctx.project().repositoryUri() : "",
                                        ctx.project() != null ? ctx.project().branch() : "main",
                                        fileTime,
                                        fileTime,
                                        null,
                                        "COMPLETED",
                                        "Analyse terminée (restaurée depuis l'historique)",
                                        100,
                                        ctx.modules() != null ? ctx.modules().size() : 0,
                                        ctx.batches() != null ? ctx.batches().size() : 0,
                                        ctx.findings() != null ? ctx.findings().size() : 0,
                                        ctx.riskAssessment() != null ? ctx.riskAssessment().score() : 0,
                                        null,
                                        ctx.targetProfile()
                                );
                                analysisSummaries.put(ctx.analysisId(), summary);
                            }
                        } catch (Exception e) {
                            log.warn("Impossible de recharger l'analyse depuis {} : {}", file, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Erreur lors de la lecture du dossier d'historique {} : {}", HISTORY_DIR, e.getMessage());
        }
    }

    private void persistAnalysisContext(AnalysisContext context) {
        try {
            if (!Files.exists(HISTORY_DIR)) {
                Files.createDirectories(HISTORY_DIR);
            }
            Path targetFile = HISTORY_DIR.resolve(context.analysisId() + ".json");
            jsonMapper.writerWithDefaultPrettyPrinter().writeValue(targetFile.toFile(), context);
            log.info("Analyse {} persistée avec succès dans {}", context.analysisId(), targetFile);
        } catch (Exception e) {
            log.warn("Échec de persistance de l'analyse {} : {}", context.analysisId(), e.getMessage());
        }
    }

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

    /**
     * Démarre une analyse en arrière-plan (asynchrone) et renvoie immédiatement son statut initial.
     */
    public AnalysisSummary startAnalysisAsync(String projectId, TargetProfile targetProfile) {
        Project project = projects.get(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Projet introuvable : " + projectId);
        }
        final TargetProfile resolvedProfile = (targetProfile != null) ? targetProfile : TargetProfile.defaultJava17Profile();
        String analysisId = UUID.randomUUID().toString().substring(0, 8);

        AnalysisSummary initialSummary = AnalysisSummary.inProgress(
                analysisId,
                project.id(),
                project.name(),
                project.repositoryUri(),
                project.branch(),
                resolvedProfile
        );
        analysisSummaries.put(analysisId, initialSummary);

        analysisExecutor.submit(() -> {
            try {
                runFullAnalysisCore(analysisId, project, resolvedProfile, summary -> analysisSummaries.put(analysisId, summary));
            } catch (Exception e) {
                log.error("Erreur lors de l'exécution asynchrone de l'analyse {} : {}", analysisId, e.getMessage(), e);
                analysisSummaries.put(analysisId, initialSummary.failed(e.getMessage()));
            }
        });

        return initialSummary;
    }

    /**
     * Exécute l'analyse de manière synchrone (bloquante jusqu'à complétion) tout en alimentant l'historique.
     */
    public AnalysisContext runFullAnalysis(String projectId, TargetProfile targetProfile) {
        Project project = projects.get(projectId);
        if (project == null) {
            throw new IllegalArgumentException("Projet introuvable : " + projectId);
        }
        TargetProfile resolvedProfile = (targetProfile != null) ? targetProfile : TargetProfile.defaultJava17Profile();
        String analysisId = UUID.randomUUID().toString().substring(0, 8);

        AnalysisSummary initialSummary = AnalysisSummary.inProgress(
                analysisId,
                project.id(),
                project.name(),
                project.repositoryUri(),
                project.branch(),
                resolvedProfile
        );
        analysisSummaries.put(analysisId, initialSummary);

        try {
            return runFullAnalysisCore(analysisId, project, resolvedProfile, summary -> analysisSummaries.put(analysisId, summary));
        } catch (Exception e) {
            analysisSummaries.put(analysisId, initialSummary.failed(e.getMessage()));
            throw e;
        }
    }

    private AnalysisContext runFullAnalysisCore(
            String analysisId,
            Project project,
            TargetProfile targetProfile,
            Consumer<AnalysisSummary> progressConsumer
    ) {
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
        log.info("Lancement de l'analyse complète [{}] pour {} sur {}", analysisId, project.name(), rootPath);

        AnalysisSummary currentSummary = analysisSummaries.get(analysisId);

        // Étape 1 : Découverte des modules Maven (15%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Découverte des modules Maven & architecture racine...", 15);
            progressConsumer.accept(currentSummary);
        }
        RepositorySnapshot snapshot = projectDiscovery.discoverSnapshot(rootPath, project.branch(), project.currentCommit());
        List<ModuleDescriptor> modules = projectDiscovery.discoverModules(rootPath);

        // Étape 2 : Découverte des Batchs Spring (25%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Détection et analyse des jobs Spring Batch...", 25);
            progressConsumer.accept(currentSummary);
        }
        List<BatchDescriptor> batches = new ArrayList<>();
        for (ModuleDescriptor mod : modules) {
            Path modDir = rootPath.resolve(mod.relativePath());
            batches.addAll(batchDiscovery.discoverBatches(rootPath, mod.artifactId(), modDir));
        }

        // Étape 3 : Inventaire des dépendances (35%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Cartographie des dépendances et compatibilités cibles...", 35);
            progressConsumer.accept(currentSummary);
        }
        List<Dependency> dependencies = dependencyAnalyzer.buildInventory(modules, targetProfile);

        // Étape 4 : Analyse du code Java (AST JavaParser) & requêtes SQL (55%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Analyse statique approfondie AST (JavaParser) & requêtes SQL...", 55);
            progressConsumer.accept(currentSummary);
        }
        List<Finding> findings = new ArrayList<>();
        for (ModuleDescriptor mod : modules) {
            Path modDir = rootPath.resolve(mod.relativePath());
            findings.addAll(codeAnalyzer.analyzeModule(rootPath, mod.artifactId(), modDir));
            findings.addAll(sqlAnalyzer.analyzeModule(rootPath, mod.artifactId(), modDir));
        }

        // Étape 5 : Analyse des couplages architecturaux (70%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Calcul des métriques de couplage architectural...", 70);
            progressConsumer.accept(currentSummary);
        }
        ArchitectureAnalyzerService.ArchitectureAnalysisResult archResult =
                architectureAnalyzer.analyzeArchitecture(rootPath, modules, batches);
        findings.addAll(archResult.findings());

        // Étape 6 : Évaluation des risques & DAG de migration (80%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Évaluation du score de risque et séquencement DAG des vagues...", 80);
            progressConsumer.accept(currentSummary);
        }
        RiskAssessment riskAssessment = riskScoring.assessRisk(modules, dependencies, findings);
        List<MigrationWave> waves = dagPlanner.buildMigrationWaves(recipeCatalog.getAllRecipes());

        // Étape 7 : Simulation Dry Run (85%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Simulation Dry Run et calcul des diffs unifiés...", 85);
            progressConsumer.accept(currentSummary);
        }
        int totalFiles = modules.stream().mapToInt(ModuleDescriptor::sourceCount).sum();
        DryRunResult dryRunResult = dryRunService.executeDryRun(rootPath, findings, totalFiles);

        // Étape 8 : Plan architectural DDD (90%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Modélisation de l'architecture DDD et Bounded Contexts...", 90);
            progressConsumer.accept(currentSummary);
        }
        DddRefactoringPlan dddPlan = dddRefactoringService.buildRefactoringPlan(
                rootPath, modules, batches, archResult.couplings()
        );

        // Étape 9 : Restructuration modulaire & packaging (95%)
        if (progressConsumer != null && currentSummary != null) {
            currentSummary = currentSummary.withProgress("Plan de réorganisation modulaire et spécialisation des communs...", 95);
            progressConsumer.accept(currentSummary);
        }
        ModuleReorganizationPlan moduleReorgPlan = moduleReorganizationService.analyzeReorganization(rootPath, modules);

        // Étape 10 : Finalisation & persistance (100%)
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
                new ArrayList<>(),
                dddPlan,
                moduleReorgPlan
        );

        analyses.put(analysisId, context);

        if (currentSummary != null) {
            AnalysisSummary completedSummary = currentSummary.completed(
                    modules.size(),
                    batches.size(),
                    findings.size(),
                    riskAssessment.score()
            );
            analysisSummaries.put(analysisId, completedSummary);
            if (progressConsumer != null) {
                progressConsumer.accept(completedSummary);
            }
        }

        persistAnalysisContext(context);

        log.info("Analyse {} terminée avec succès : {} modules, {} batchs, {} findings, risque {}",
                analysisId, modules.size(), batches.size(), findings.size(), riskAssessment.score());

        return context;
    }

    public List<AnalysisSummary> listAnalysisSummaries() {
        return analysisSummaries.values().stream()
                .sorted(Comparator.comparing(AnalysisSummary::startTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public Optional<AnalysisSummary> getAnalysisSummary(String analysisId) {
        return Optional.ofNullable(analysisSummaries.get(analysisId));
    }

    public boolean deleteAnalysis(String analysisId) {
        analyses.remove(analysisId);
        analysisSummaries.remove(analysisId);
        try {
            Path file = HISTORY_DIR.resolve(analysisId + ".json");
            if (Files.exists(file)) {
                Files.delete(file);
            }
            return true;
        } catch (Exception e) {
            log.warn("Impossible de supprimer le fichier d'analyse {} : {}", analysisId, e.getMessage());
            return false;
        }
    }

    public Optional<AnalysisContext> getAnalysis(String analysisId) {
        return Optional.ofNullable(analyses.get(analysisId));
    }

    public Optional<AnalysisContext> getLatestAnalysis() {
        return listAnalysisSummaries().stream()
                .filter(s -> "COMPLETED".equalsIgnoreCase(s.status()))
                .map(s -> analyses.get(s.analysisId()))
                .filter(Objects::nonNull)
                .findFirst()
                .or(() -> analyses.values().stream().reduce((first, second) -> second));
    }

    public AnalysisContext importAnalysis(AnalysisContext importedCtx) {
        if (importedCtx == null) {
            throw new IllegalArgumentException("Le contexte d'analyse importé ne peut pas être null");
        }
        if (importedCtx.project() != null) {
            projects.put(importedCtx.project().id(), importedCtx.project());
        }
        analyses.put(importedCtx.analysisId(), importedCtx);

        AnalysisSummary summary = new AnalysisSummary(
                importedCtx.analysisId(),
                importedCtx.project() != null ? importedCtx.project().id() : "unknown",
                importedCtx.project() != null ? importedCtx.project().name() : "Projet importé",
                importedCtx.project() != null ? importedCtx.project().repositoryUri() : "",
                importedCtx.project() != null ? importedCtx.project().branch() : "main",
                Instant.now(),
                Instant.now(),
                0L,
                "COMPLETED",
                "Analyse importée",
                100,
                importedCtx.modules() != null ? importedCtx.modules().size() : 0,
                importedCtx.batches() != null ? importedCtx.batches().size() : 0,
                importedCtx.findings() != null ? importedCtx.findings().size() : 0,
                importedCtx.riskAssessment() != null ? importedCtx.riskAssessment().score() : 0,
                null,
                importedCtx.targetProfile()
        );
        analysisSummaries.put(importedCtx.analysisId(), summary);
        persistAnalysisContext(importedCtx);

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
                ctx.architectureCouplings(), ctx.waves(), ctx.dryRunResult(), valResult, ctx.performanceComparisons(),
                ctx.dddPlan(), ctx.moduleReorganizationPlan()
        );
        analyses.put(analysisId, updated);
        return valResult;
    }

    public Path resolveProjectRoot(Project project) {
        Path rootPath = Paths.get(project.repositoryUri());
        if (!Files.exists(rootPath)) {
            Path fallback = Paths.get("..").resolve(project.repositoryUri()).normalize();
            if (Files.exists(fallback)) {
                rootPath = fallback;
            }
        }
        return rootPath;
    }

    public ModuleReorganizationPlan getModuleReorganizationPlan(String analysisId) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }
        if (ctx.moduleReorganizationPlan() != null) {
            return ctx.moduleReorganizationPlan();
        }
        Path rootPath = resolveProjectRoot(ctx.project());
        ModuleReorganizationPlan plan = moduleReorganizationService.analyzeReorganization(rootPath, ctx.modules());
        AnalysisContext updated = new AnalysisContext(
                ctx.analysisId(), ctx.project(), ctx.snapshot(), ctx.modules(), ctx.batches(),
                ctx.dependencies(), ctx.targetProfile(), ctx.findings(), ctx.riskAssessment(),
                ctx.architectureCouplings(), ctx.waves(), ctx.dryRunResult(), ctx.validationResult(),
                ctx.performanceComparisons(), ctx.dddPlan(), plan
        );
        analyses.put(analysisId, updated);
        return plan;
    }

    public ModuleReorganizationPlan previewModuleReorganization(String analysisId, ModuleReorganizationPlan customizedPlan) {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }
        Path rootPath = resolveProjectRoot(ctx.project());
        ModuleReorganizationPlan preview = moduleReorganizationService.previewCustomizedPlan(rootPath, customizedPlan);
        AnalysisContext updated = new AnalysisContext(
                ctx.analysisId(), ctx.project(), ctx.snapshot(), ctx.modules(), ctx.batches(),
                ctx.dependencies(), ctx.targetProfile(), ctx.findings(), ctx.riskAssessment(),
                ctx.architectureCouplings(), ctx.waves(), ctx.dryRunResult(), ctx.validationResult(),
                ctx.performanceComparisons(), ctx.dddPlan(), preview
        );
        analyses.put(analysisId, updated);
        return preview;
    }

    public ModuleReorganizationPlan applyModuleReorganization(String analysisId, ModuleReorganizationPlan planToApply) throws Exception {
        AnalysisContext ctx = analyses.get(analysisId);
        if (ctx == null) {
            throw new IllegalArgumentException("Analyse introuvable : " + analysisId);
        }
        Path rootPath = resolveProjectRoot(ctx.project());
        ModuleReorganizationPlan applied = moduleReorganizationService.applyReorganization(rootPath, planToApply);
        AnalysisContext updated = new AnalysisContext(
                ctx.analysisId(), ctx.project(), ctx.snapshot(), ctx.modules(), ctx.batches(),
                ctx.dependencies(), ctx.targetProfile(), ctx.findings(), ctx.riskAssessment(),
                ctx.architectureCouplings(), ctx.waves(), ctx.dryRunResult(), ctx.validationResult(),
                ctx.performanceComparisons(), ctx.dddPlan(), applied
        );
        analyses.put(analysisId, updated);
        return applied;
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

    public com.refacto.migration.code.DddRefactoringService getDddRefactoringService() {
        return dddRefactoringService;
    }

    public ModuleReorganizationService getModuleReorganizationService() {
        return moduleReorganizationService;
    }
}
