package com.refacto.migration.core.model;

import java.util.List;
import java.util.Set;

public record ModuleDescriptor(
        String id,
        String name,
        String relativePath,
        String groupId,
        String artifactId,
        String version,
        String packaging,
        String javaVersion,
        Set<String> frameworks,
        List<String> dependencies,
        List<String> dependentModules,
        int sourceCount,
        int testCount,
        boolean hasSpringBatch,
        boolean hasJpa
) {}
