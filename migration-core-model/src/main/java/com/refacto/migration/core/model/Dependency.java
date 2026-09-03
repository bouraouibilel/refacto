package com.refacto.migration.core.model;

import java.util.List;

public record Dependency(
        String groupId,
        String artifactId,
        String currentVersion,
        String targetVersion,
        String scope,
        boolean direct,
        List<String> modules,
        String managedBy,
        boolean conflict,
        String migrationStatus,
        int breakingChangeCount
) {
    public String getCoordinates() {
        return groupId + ":" + artifactId;
    }
}
