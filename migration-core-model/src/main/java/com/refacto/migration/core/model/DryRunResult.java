package com.refacto.migration.core.model;

import java.util.List;

public record DryRunResult(
        int filesScanned,
        int findingsCount,
        int potentialChangesCount,
        int autoSafeCount,
        int autoWithTestsCount,
        int reviewRequiredCount,
        int manualOnlyCount,
        List<DiffEntry> diffEntries
) {}
