package com.refacto.migration.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.refacto.migration.core.model.BatchDescriptor;
import com.refacto.migration.core.model.DiffEntry;
import com.refacto.migration.core.model.Finding;
import com.refacto.migration.core.model.MigrationReport;
import com.refacto.migration.core.model.PerformanceComparison;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Moteur de génération de rapports de migration (Section 65) : HTML interactif, Markdown et JSON.
 */
public class ReportingEngineService {

    private final ObjectMapper jsonMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String generateJsonReport(MigrationReport report) throws Exception {
        return jsonMapper.writeValueAsString(report);
    }

    public String generateMarkdownReport(MigrationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Rapport de Migration Technique - ").append(report.projectName()).append("\n\n");
        sb.append("**Date d'analyse :** ").append(report.generatedAt()).append("\n");
        sb.append("**Score de Risque :** ").append(report.riskAssessment().score()).append("/100 (")
                .append(report.riskAssessment().level()).append(")\n\n");

        sb.append("## 1. Synthèse Exécutive\n\n");
        report.executiveSummary().forEach((k, v) -> sb.append("- **").append(k).append(" :** ").append(v).append("\n"));
        sb.append("\n");

        sb.append("## 2. Inventaire de l'Application\n\n");
        sb.append("- **Modules :** ").append(report.modules().size()).append("\n");
        sb.append("- **Traitements Batch (").append(report.batches().size()).append(") :**\n");
        for (BatchDescriptor b : report.batches()) {
            sb.append("  - `").append(b.jobName()).append("` (").append(b.steps().size()).append(" steps)\n");
        }
        sb.append("- **Dépendances :** ").append(report.dependencies().size()).append("\n\n");

        sb.append("## 3. Findings Techniques Majeurs (").append(report.findings().size()).append(")\n\n");
        sb.append("| Règle | Sévérité | Automatisation | Module | Fichier | Description |\n");
        sb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");
        for (Finding f : report.findings()) {
            sb.append("| `").append(f.recipeId()).append("` | ")
                    .append(f.severity()).append(" | ")
                    .append(f.automationLevel()).append(" | ")
                    .append(f.moduleName()).append(" | `")
                    .append(f.filePath() != null ? f.filePath() : "").append("` | ")
                    .append(f.description().replace("\n", " ")).append(" |\n");
        }
        sb.append("\n");

        sb.append("## 4. Résultats de Performance & Validation Fonctionnelle\n\n");
        if (report.performanceComparisons() != null && !report.performanceComparisons().isEmpty()) {
            sb.append("| Batch | Volume | Avant | Après | Gain (%) | Perf | Fonct | Résultat |\n");
            sb.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n");
            for (PerformanceComparison p : report.performanceComparisons()) {
                sb.append("| ").append(p.batchName()).append(" | ")
                        .append(p.volumeRecords()).append(" | ")
                        .append(p.durationBeforeFormatted()).append(" | ")
                        .append(p.durationAfterFormatted()).append(" | ")
                        .append(p.gainPercent()).append(" % | ")
                        .append(p.performanceStatus()).append(" | ")
                        .append(p.functionalStatus()).append(" | **")
                        .append(p.resultValidated() ? "VALIDATED" : "REJECTED").append("** |\n");
            }
        } else {
            sb.append("_Aucune baseline de performance enregistrée pour cette campagne._\n");
        }
        sb.append("\n");

        sb.append("## 5. Recommandations Finales\n\n");
        for (String rec : report.recommendations()) {
            sb.append("1. ").append(rec).append("\n");
        }

        return sb.toString();
    }

    public String generateHtmlReport(MigrationReport report) {
        String riskColor = report.riskAssessment().score() >= 70 ? "#dc2626" :
                (report.riskAssessment().score() >= 40 ? "#f59e0b" : "#16a34a");

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <html lang="fr">
                <head>
                    <meta charset="UTF-8">
                    <title>Rapport de Migration - """)
                .append(report.projectName())
                .append("""
                </title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 30px; background: #f8fafc; color: #1e293b; }
                    .container { max-width: 1200px; margin: 0 auto; background: white; border-radius: 12px; box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1); padding: 40px; }
                    h1 { font-size: 28px; color: #0f172a; margin-bottom: 8px; }
                    .badge { display: inline-block; padding: 4px 12px; border-radius: 9999px; font-weight: 600; font-size: 13px; }
                    .kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 20px; margin: 30px 0; }
                    .kpi-card { background: #f1f5f9; padding: 20px; border-radius: 8px; border-left: 4px solid #3b82f6; }
                    .kpi-title { font-size: 13px; color: #64748b; text-transform: uppercase; font-weight: 600; }
                    .kpi-value { font-size: 28px; font-weight: 700; color: #0f172a; margin-top: 6px; }
                    table { width: 100%; border-collapse: collapse; margin: 20px 0; font-size: 14px; }
                    th, td { padding: 12px 16px; text-align: left; border-bottom: 1px solid #e2e8f0; }
                    th { background: #f8fafc; color: #475569; font-weight: 600; }
                    .sev-CRITICAL { color: #dc2626; font-weight: bold; }
                    .sev-HIGH { color: #ea580c; font-weight: bold; }
                    .sev-MEDIUM { color: #d97706; }
                    .sev-LOW { color: #16a34a; }
                </style>
            </head>
            <body>
            <div class="container">
            """);

        sb.append("<h1>Rapport de Migration - ").append(report.projectName()).append("</h1>");
        sb.append("<p style='color: #64748b;'>Généré le ").append(report.generatedAt()).append("</p>");

        sb.append("<div class='kpi-grid'>");
        sb.append("<div class='kpi-card' style='border-left-color: ").append(riskColor).append(";'>")
                .append("<div class='kpi-title'>Score de Risque</div>")
                .append("<div class='kpi-value' style='color: ").append(riskColor).append(";'>")
                .append(report.riskAssessment().score()).append("/100</div></div>");

        sb.append("<div class='kpi-card'><div class='kpi-title'>Modules Détectés</div>")
                .append("<div class='kpi-value'>").append(report.modules().size()).append("</div></div>");

        sb.append("<div class='kpi-card'><div class='kpi-title'>Traitements Batch</div>")
                .append("<div class='kpi-value'>").append(report.batches().size()).append("</div></div>");

        sb.append("<div class='kpi-card'><div class='kpi-title'>Findings Techniques</div>")
                .append("<div class='kpi-value'>").append(report.findings().size()).append("</div></div>");
        sb.append("</div>");

        sb.append("<h2>Findings Techniques par Règle</h2>");
        sb.append("<table><thead><tr><th>Règle</th><th>Sévérité</th><th>Automatisation</th><th>Module</th><th>Fichier</th><th>Description</th></tr></thead><tbody>");
        for (Finding f : report.findings()) {
            sb.append("<tr>")
                    .append("<td><code>").append(f.recipeId()).append("</code></td>")
                    .append("<td class='sev-").append(f.severity()).append("'>").append(f.severity()).append("</td>")
                    .append("<td>").append(f.automationLevel()).append("</td>")
                    .append("<td>").append(f.moduleName()).append("</td>")
                    .append("<td><code>").append(f.filePath() != null ? f.filePath() : "").append("</code></td>")
                    .append("<td>").append(f.description()).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");

        sb.append("</div></body></html>");
        return sb.toString();
    }

    public void saveReports(MigrationReport report, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("migration-report.json"), generateJsonReport(report), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("migration-report.md"), generateMarkdownReport(report), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("migration-report.html"), generateHtmlReport(report), StandardCharsets.UTF_8);
    }
}
