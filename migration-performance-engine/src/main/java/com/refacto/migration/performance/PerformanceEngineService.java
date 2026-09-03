package com.refacto.migration.performance;

import com.refacto.migration.core.model.PerformanceBaseline;
import com.refacto.migration.core.model.PerformanceComparison;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moteur de gestion des baselines de performance et de double validation fonctionnelle + performance (Sections 43, 44, 45).
 */
public class PerformanceEngineService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceEngineService.class);

    private final Map<String, PerformanceBaseline> baselinesByBatch = new ConcurrentHashMap<>();

    public PerformanceBaseline recordBaseline(
            String batchName,
            String commitHash,
            String environment,
            String dbSnapshotFingerprint,
            List<String> inputFiles,
            Map<String, String> parameters,
            long recordCount,
            long durationMs,
            String resultFingerprint
    ) {
        PerformanceBaseline baseline = PerformanceBaseline.of(
                UUID.randomUUID().toString().substring(0, 8),
                batchName,
                commitHash,
                environment,
                dbSnapshotFingerprint,
                inputFiles,
                parameters,
                recordCount,
                durationMs,
                resultFingerprint
        );

        baselinesByBatch.put(batchName, baseline);
        log.info("Baseline enregistrée pour {}: {} records en {}", batchName, recordCount, baseline.durationFormatted());
        return baseline;
    }

    public Optional<PerformanceBaseline> getBaseline(String batchName) {
        return Optional.ofNullable(baselinesByBatch.get(batchName));
    }

    public PerformanceComparison evaluateRun(
            String batchName,
            long recordCount,
            long durationAfterMs,
            String resultChecksumAfter
    ) {
        PerformanceBaseline baseline = baselinesByBatch.get(batchName);
        if (baseline == null) {
            // Self-baseline
            return PerformanceComparison.compare(
                    batchName,
                    recordCount,
                    durationAfterMs,
                    durationAfterMs,
                    resultChecksumAfter,
                    resultChecksumAfter
            );
        }

        return PerformanceComparison.compare(
                batchName,
                recordCount,
                baseline.executionDurationMs(),
                durationAfterMs,
                baseline.resultFingerprint(),
                resultChecksumAfter
        );
    }
}
