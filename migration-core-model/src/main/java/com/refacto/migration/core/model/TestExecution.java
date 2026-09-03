package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.ValidationStatus;

public record TestExecution(
        int totalTests,
        int passedTests,
        int failedTests,
        int skippedTests,
        long durationMs,
        ValidationStatus status,
        String failureSummary
) {}
