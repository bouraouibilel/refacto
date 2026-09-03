package com.refacto.migration.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PerformanceBaseline(
        String id,
        String batchName,
        String commitHash,
        String environment,
        String dbSnapshotFingerprint,
        List<String> inputFiles,
        Map<String, String> parameters,
        long recordCount,
        long executionDurationMs,
        String durationFormatted,
        double throughputRecordsPerSec,
        String resultFingerprint,
        Instant recordedAt
) {
    public static PerformanceBaseline of(
            String id,
            String batchName,
            String commitHash,
            String environment,
            String dbSnapshotFingerprint,
            List<String> inputFiles,
            Map<String, String> parameters,
            long recordCount,
            long executionDurationMs,
            String resultFingerprint
    ) {
        long seconds = executionDurationMs / 1000;
        long minutes = seconds / 60;
        long remainingSec = seconds % 60;
        String formatted = minutes > 0 ? String.format("%dm%02ds", minutes, remainingSec) : String.format("%d.%03ds", seconds, executionDurationMs % 1000);
        double throughput = executionDurationMs > 0 ? (recordCount * 1000.0) / executionDurationMs : 0.0;

        return new PerformanceBaseline(
                id,
                batchName,
                commitHash,
                environment,
                dbSnapshotFingerprint,
                inputFiles,
                parameters,
                recordCount,
                executionDurationMs,
                formatted,
                throughput,
                resultFingerprint,
                Instant.now()
        );
    }
}
