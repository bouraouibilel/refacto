package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.ValidationStatus;

public record BuildExecution(
        String command,
        int exitCode,
        long durationMs,
        ValidationStatus status,
        String logs
) {}
