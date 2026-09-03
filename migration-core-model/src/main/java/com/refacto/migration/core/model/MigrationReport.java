package com.refacto.migration.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MigrationReport(
        String projectId,
        String projectName,
        Instant generatedAt,
        Map<String, Object> executiveSummary,
        RepositorySnapshot repositorySnapshot,
        List<ModuleDescriptor> modules,
        List<BatchDescriptor> batches,
        List<Dependency> dependencies,
        TargetProfile targetProfile,
        RiskAssessment riskAssessment,
        List<Finding> findings,
        List<DiffEntry> executedChanges,
        List<DiffEntry> rejectedChanges,
        ValidationResult validationResult,
        List<PerformanceComparison> performanceComparisons,
        List<ArchitectureCoupling> architectureCouplings,
        List<String> recommendations
) {}
