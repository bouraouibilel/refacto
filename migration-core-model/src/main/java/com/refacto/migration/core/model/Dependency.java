package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonIgnore
    public String getCoordinates() {
        return groupId + ":" + artifactId;
    }
}
