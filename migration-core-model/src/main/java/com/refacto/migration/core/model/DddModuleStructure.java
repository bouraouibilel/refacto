package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structure d'un module Maven cible dans la composition DDD réorganisée.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DddModuleStructure(
        String moduleArtifactId,
        String boundedContextId,
        DddLayer layer,
        String description,
        List<String> mavenDependencies,
        List<String> assignedClasses
) {}
