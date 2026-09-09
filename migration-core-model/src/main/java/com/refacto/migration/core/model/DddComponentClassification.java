package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Classification d'une classe selon les couches et bounded contexts DDD,
 * avec préconisation de relocalisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DddComponentClassification(
        String className,
        String currentModule,
        String currentPackage,
        DddLayer layer,
        String targetBoundedContext,
        String targetModule,
        String targetPackage,
        String rationale
) {}
