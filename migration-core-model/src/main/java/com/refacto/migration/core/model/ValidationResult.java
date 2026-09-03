package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.ValidationStatus;

public record ValidationResult(
        ValidationStatus buildStatus,
        ValidationStatus unitTestStatus,
        ValidationStatus integrationTestStatus,
        ValidationStatus performanceStatus,
        ValidationStatus functionalStatus,
        ValidationStatus overallStatus,
        String summaryMessage,
        BuildExecution buildExecution,
        TestExecution unitTestExecution,
        TestExecution integrationTestExecution
) {
    public static ValidationResult success(String message) {
        return new ValidationResult(
                ValidationStatus.PASS,
                ValidationStatus.PASS,
                ValidationStatus.PASS,
                ValidationStatus.PASS,
                ValidationStatus.PASS,
                ValidationStatus.PASS,
                message,
                null,
                null,
                null
        );
    }
}
