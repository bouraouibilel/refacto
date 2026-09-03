package com.refacto.migration.app;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.PerformanceComparison;
import com.refacto.migration.core.model.Project;
import com.refacto.migration.core.model.TargetProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Initialiseur automatique de données de démonstration au démarrage.
 * Précharge et analyse automatiquement le projet 'sample-legacy-app' pour
 * que le tableau de bord soit immédiatement peuplé d'une analyse concrète.
 */
@Component
public class DemoDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final MigrationOrchestratorService orchestrator;

    public DemoDataInitializer(MigrationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(String... args) {
        try {
            Path samplePath = resolveSamplePath();
            if (samplePath != null && Files.exists(samplePath)) {
                log.info("Préchargement et analyse automatique du projet témoin : {}", samplePath);

                Project project = orchestrator.registerProject("Sample Legacy APP", samplePath.toAbsolutePath().toString(), "main");
                MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(
                        project.id(),
                        TargetProfile.defaultJava17Profile()
                );

                // Enregistrer baseline & comparaison de performance de démonstration (Sections 43-45)
                orchestrator.getPerformanceService().recordBaseline(
                        "paymentJob",
                        "commit-e789a1",
                        "staging-perf",
                        "db-snapshot-2026",
                        List.of("input_payments_sample.csv"),
                        Map.of("chunkSize", "100"),
                        850_000,
                        822_000, // 13m42s
                        "checksum-sha256-abc123"
                );

                PerformanceComparison comparison = orchestrator.getPerformanceService().evaluateRun(
                        "paymentJob",
                        850_000,
                        436_000, // 7m16s
                        "checksum-sha256-abc123"
                );

                orchestrator.addPerformanceComparison(context.analysisId(), comparison);

                log.info("Projet témoin analysé avec succès ! Analysis ID: {}, {} findings, risque {}",
                        context.analysisId(), context.findings().size(), context.riskAssessment().score());
            } else {
                log.warn("Le répertoire sample-legacy-app n'a pas été trouvé. Aucune analyse préchargée.");
            }
        } catch (Exception e) {
            log.error("Erreur lors du préchargement de la démo : {}", e.getMessage(), e);
        }
    }

    private Path resolveSamplePath() {
        Path direct = Paths.get("sample-legacy-app");
        if (Files.exists(direct)) return direct;

        Path parent = Paths.get("../sample-legacy-app");
        if (Files.exists(parent)) return parent;

        Path absolute = Paths.get("d:/work/sample/refacto/sample-legacy-app");
        if (Files.exists(absolute)) return absolute;

        return null;
    }
}
