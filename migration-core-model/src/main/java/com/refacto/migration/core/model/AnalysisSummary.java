package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Résumé synthétique d'une analyse permettant le suivi dans l'historique
 * et l'affichage de la progression en temps réel pour les analyses asynchrones.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisSummary(
        String analysisId,
        String projectId,
        String projectName,
        String projectPath,
        String branch,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        String status,
        String currentStep,
        int progressPercent,
        int modulesCount,
        int batchesCount,
        int findingsCount,
        int riskScore,
        String errorMessage,
        TargetProfile targetProfile
) {
    public static AnalysisSummary inProgress(String analysisId, String projectId, String projectName, String projectPath, String branch, TargetProfile targetProfile) {
        return new AnalysisSummary(
                analysisId,
                projectId,
                projectName,
                projectPath,
                branch,
                Instant.now(),
                null,
                null,
                "IN_PROGRESS",
                "Initialisation de l'analyse...",
                5,
                0,
                0,
                0,
                0,
                null,
                targetProfile
        );
    }

    public AnalysisSummary withProgress(String newStep, int newPercent) {
        Long currentDuration = (startTime != null) ? java.time.Duration.between(startTime, Instant.now()).toMillis() : null;
        return new AnalysisSummary(
                analysisId, projectId, projectName, projectPath, branch,
                startTime, endTime, currentDuration, status,
                newStep, newPercent, modulesCount, batchesCount, findingsCount, riskScore,
                errorMessage, targetProfile
        );
    }

    public AnalysisSummary completed(int newModulesCount, int newBatchesCount, int newFindingsCount, int newRiskScore) {
        Instant now = Instant.now();
        Long totalDuration = (startTime != null) ? java.time.Duration.between(startTime, now).toMillis() : null;
        return new AnalysisSummary(
                analysisId, projectId, projectName, projectPath, branch,
                startTime, now, totalDuration, "COMPLETED",
                "Analyse terminée avec succès", 100,
                newModulesCount, newBatchesCount, newFindingsCount, newRiskScore,
                null, targetProfile
        );
    }

    public AnalysisSummary failed(String error) {
        Instant now = Instant.now();
        Long totalDuration = (startTime != null) ? java.time.Duration.between(startTime, now).toMillis() : null;
        return new AnalysisSummary(
                analysisId, projectId, projectName, projectPath, branch,
                startTime, now, totalDuration, "FAILED",
                "Échec de l'analyse", progressPercent,
                modulesCount, batchesCount, findingsCount, riskScore,
                error, targetProfile
        );
    }
}
