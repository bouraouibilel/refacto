package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Plan global de réorganisation en sous-projets par packaging.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModuleReorganizationPlan(
        String parentPomPath,
        List<PackagingSubProjectGroup> groups,
        List<String> unassignedModules,
        String rootPomDiff,
        Map<String, String> subProjectPomDiffs,
        List<String> gitMoveCommands,
        boolean applied,
        String summary
) {
    public static ModuleReorganizationPlan empty() {
        return new ModuleReorganizationPlan(
                "pom.xml",
                List.of(),
                List.of(),
                "",
                Map.of(),
                List.of(),
                false,
                "Aucun module de packaging détecté pour la réorganisation."
        );
    }
}
