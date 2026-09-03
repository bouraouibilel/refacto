package com.refacto.migration.core.model;

import com.refacto.migration.core.enums.BuildSystem;
import java.time.Instant;

public record RepositorySnapshot(
        String commit,
        String branch,
        Instant analysisDate,
        BuildSystem buildSystem,
        String detectedJavaVersion,
        String rootDirectory
) {}
