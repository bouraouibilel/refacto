package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Plan complet de refactoring de composition et de migration DDD.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DddRefactoringPlan(
        List<DddBoundedContext> boundedContexts,
        List<DddComponentClassification> classifications,
        List<DddModuleStructure> targetModules,
        String executiveSummary,
        int decoupledEntitiesCount,
        int isolatedModulesCount
) {}
