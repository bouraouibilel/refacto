package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Contexte délimité (Bounded Context) identifié dans le projet pour regrouper et isoler des modules/domaines.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DddBoundedContext(
        String id,
        String name,
        String description,
        List<String> sourceModules,
        List<String> mainEntities,
        List<String> batchJobs,
        String recommendedPackagePrefix
) {}
