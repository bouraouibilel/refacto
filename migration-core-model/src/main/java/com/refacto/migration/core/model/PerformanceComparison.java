package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.ValidationStatus;

public record PerformanceComparison(
        String batchName,
        long volumeRecords,
        long durationBeforeMs,
        long durationAfterMs,
        String durationBeforeFormatted,
        String durationAfterFormatted,
        double gainPercent,
        ValidationStatus performanceStatus,
        ValidationStatus functionalStatus,
        boolean resultValidated,
        String functionalChecksumBefore,
        String functionalChecksumAfter
) {
    public static PerformanceComparison compare(
            String batchName,
            long volumeRecords,
            long durationBeforeMs,
            long durationAfterMs,
            String checksumBefore,
            String checksumAfter
    ) {
        double gain = durationBeforeMs > 0 ?
                ((double) (durationBeforeMs - durationAfterMs) / durationBeforeMs) * 100.0 : 0.0;

        String formattedBefore = formatDuration(durationBeforeMs);
        String formattedAfter = formatDuration(durationAfterMs);

        ValidationStatus perfStatus = durationAfterMs <= durationBeforeMs ? ValidationStatus.PASS : ValidationStatus.FAIL;
        boolean checksumMatches = checksumBefore != null && checksumBefore.equals(checksumAfter);
        ValidationStatus funcStatus = checksumMatches ? ValidationStatus.PASS : ValidationStatus.FAIL;
        boolean validated = (perfStatus == ValidationStatus.PASS) && (funcStatus == ValidationStatus.PASS);

        return new PerformanceComparison(
                batchName,
                volumeRecords,
                durationBeforeMs,
                durationAfterMs,
                formattedBefore,
                formattedAfter,
                Math.round(gain * 10.0) / 10.0,
                perfStatus,
                funcStatus,
                validated,
                checksumBefore,
                checksumAfter
        );
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long remSec = seconds % 60;
        if (minutes > 0) {
            return String.format("%dm%02ds", minutes, remSec);
        }
        return String.format("%d.%03ds", seconds, ms % 1000);
    }
}
