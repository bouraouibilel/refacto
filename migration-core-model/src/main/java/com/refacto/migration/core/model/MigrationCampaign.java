package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.ValidationStatus;
import java.time.Instant;
import java.util.List;

public record MigrationCampaign(
        String id,
        String projectId,
        String analysisId,
        TargetProfile targetProfile,
        List<MigrationWave> waves,
        int overallRiskScore,
        ValidationStatus status,
        Instant createdAt,
        Instant completedAt
) {}
