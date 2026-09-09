package com.refacto.migration.app;

import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EndToEndMigrationPlatformTest {

    @Autowired
    private MigrationOrchestratorService orchestrator;

    @Test
    void shouldExecuteFullMigrationWorkflowOnSampleLegacyApp() throws Exception {
        Path sampleAppPath = Paths.get("d:/work/sample/refacto/sample-legacy-app");
        assertThat(sampleAppPath.toFile()).exists();

        // 1. Register Project
        Project project = orchestrator.registerProject("Sample Legacy APP", sampleAppPath.toString(), "main");
        assertThat(project.id()).isNotNull();

        // 2. Run Full Analysis
        MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(
                project.id(),
                TargetProfile.defaultJava17Profile()
        );

        // 3. Verify Discovery (Modules & Spring Batch)
        assertThat(context.modules()).hasSize(4); // root + 3 submodules
        assertThat(context.modules()).anyMatch(m -> m.artifactId().equals("batch-payment") && m.hasSpringBatch());
        assertThat(context.modules()).anyMatch(m -> m.artifactId().equals("payment-model") && m.hasJpa());

        assertThat(context.batches()).hasSize(1);
        BatchDescriptor batchJob = context.batches().get(0);
        assertThat(batchJob.jobName()).isEqualTo("paymentJob");
        assertThat(batchJob.steps()).isNotEmpty();
        assertThat(batchJob.steps().get(0).chunkSize()).isEqualTo(100);

        // 4. Verify Dependencies & Target Matrix
        assertThat(context.dependencies()).isNotEmpty();
        Dependency batchDep = context.dependencies().stream()
                .filter(d -> d.artifactId().equals("spring-batch-core"))
                .findFirst().orElseThrow();
        assertThat(batchDep.currentVersion()).contains("4.3.5");
        assertThat(batchDep.targetVersion()).isEqualTo("5.1.2");
        assertThat(batchDep.breakingChangeCount()).isGreaterThan(0);

        // 5. Verify Findings Detection across all rules
        List<Finding> findings = context.findings();
        assertThat(findings).isNotEmpty();

        // APP-DB-001 (DB access outside DAO)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-001") && f.category() == Category.DATABASE);

        // APP-DB-003 (SELECT FOR UPDATE)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-003") && f.automationLevel() == AutomationLevel.MANUAL_ONLY);

        // APP-DB-004 (DB calls in loop)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-004") && f.potentialRoundTrips() != null);

        // APP-DB-005 (SELECT *)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-005"));

        // APP-DB-006 (Bulk update in chunks)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-006"));

        // APP-CODE-001 (SRP)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-CODE-001") && f.category() == Category.CODE);

        // APP-CODE-002 (Niveau d'abstraction)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-CODE-002"));

        // APP-ARCH-001 (Shared business DAO)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-ARCH-001") && f.category() == Category.ARCHITECTURE);

        // JAVA17-001 (Try-with-resources)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("JAVA17-001") && f.category() == Category.JAVA17);

        // JAVA17-002 (File -> Path)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("JAVA17-002"));

        // JAVA17-003 (Boucles imbriquees -> Stream API)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("JAVA17-003") && f.category() == Category.JAVA17);

        // JAVA17-007 (Optional JPA)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("JAVA17-007") && f.severity() == Severity.HIGH);

        // 6. Verify Architecture Couplings & APP-commons
        List<ArchitectureCoupling> couplings = context.architectureCouplings();
        assertThat(couplings).isNotEmpty();
        assertThat(couplings).anyMatch(c -> c.componentName().equals("SharedCustomerDAO") && !c.eligibleForCommons());
        assertThat(couplings).anyMatch(c -> c.componentName().equals("StringUtilsHelper") && c.eligibleForCommons());

        // 7. Verify Risk Assessment & Score (Section 38)
        RiskAssessment risk = context.riskAssessment();
        assertThat(risk.score()).isGreaterThanOrEqualTo(70); // High risk project
        assertThat(risk.level()).isEqualTo("HIGH");
        assertThat(risk.factors()).isNotEmpty();

        // 8. Verify DAG Waves (Section 21 & 37)
        List<MigrationWave> waves = context.waves();
        assertThat(waves).isNotEmpty();
        assertThat(waves.get(0).type().name()).isEqualTo("WAVE_0_BASELINE");

        // 9. Verify Dry Run & Diffs
        DryRunResult dryRun = context.dryRunResult();
        assertThat(dryRun).isNotNull();
        assertThat(dryRun.diffEntries()).isNotEmpty();

        // 10. Performance Baseline & Dual Validation (Section 43-45)
        orchestrator.getPerformanceService().recordBaseline(
                "paymentJob",
                "c0ffee1",
                "staging-ref",
                "db-snap-2026",
                List.of("input_payments.csv"),
                java.util.Map.of("chunkSize", "100"),
                850_000,
                822_000, // 13m42s
                "fingerprint-sha-xyz"
        );

        PerformanceComparison perfComparison = orchestrator.getPerformanceService().evaluateRun(
                "paymentJob",
                850_000,
                436_000, // 7m16s
                "fingerprint-sha-xyz"
        );

        assertThat(perfComparison.gainPercent()).isCloseTo(46.9, org.assertj.core.data.Offset.offset(0.2));
        assertThat(perfComparison.resultValidated()).isTrue();
        orchestrator.addPerformanceComparison(context.analysisId(), perfComparison);

        // 11. Report Generation (HTML, JSON, Markdown)
        MigrationReport report = orchestrator.generateReport(context.analysisId());
        String html = orchestrator.getReportingService().generateHtmlReport(report);
        String md = orchestrator.getReportingService().generateMarkdownReport(report);
        String json = orchestrator.getReportingService().generateJsonReport(report);

        assertThat(html).contains("Sample Legacy APP").contains("APP-DB-001");
        assertThat(md).contains("Score de Risque").contains("paymentJob");
        assertThat(json).contains("executiveSummary");

        // 12. Full Analysis Snapshot Export & Import
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String exportedJson = mapper.writeValueAsString(context);
        assertThat(exportedJson).contains("paymentJob").contains("JAVA17-003");

        MigrationOrchestratorService.AnalysisContext imported = mapper.readValue(
                exportedJson,
                MigrationOrchestratorService.AnalysisContext.class
        );
        assertThat(imported.analysisId()).isEqualTo(context.analysisId());
        assertThat(imported.modules()).hasSize(context.modules().size());
        assertThat(imported.findings()).hasSize(context.findings().size());
        assertThat(imported.riskAssessment().score()).isEqualTo(context.riskAssessment().score());

        MigrationOrchestratorService.AnalysisContext registered = orchestrator.importAnalysis(imported);
        assertThat(orchestrator.getAnalysis(registered.analysisId())).isPresent();
    }
}
