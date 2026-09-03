package com.refacto.migration.core.model;

import java.time.Instant;

public record Project(
        String id,
        String name,
        String repositoryUri,
        String branch,
        String currentCommit,
        Instant createdAt,
        Instant updatedAt
) {
    public static Project create(String id, String name, String repositoryUri, String branch, String commit) {
        Instant now = Instant.now();
        return new Project(id, name, repositoryUri, branch, commit, now, now);
    }
}
