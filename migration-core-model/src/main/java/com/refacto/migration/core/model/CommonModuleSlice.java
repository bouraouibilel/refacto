package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Représente la tranche spécialisée et épurée d'un module commun (ex: legacy-common)
 * dupliquée/découpée au sein d'un sous-projet (Bounded Context).
 * Seules les classes effectivement référencées par les modules du sous-projet y sont conservées.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommonModuleSlice(
        String originalModuleName,
        String targetModuleName,
        String targetRelativePath,
        List<String> retainedClasses,
        List<String> prunedClasses,
        boolean included
) {
    public CommonModuleSlice withIncluded(boolean newIncluded) {
        return new CommonModuleSlice(originalModuleName, targetModuleName, targetRelativePath, retainedClasses, prunedClasses, newIncluded);
    }

    public CommonModuleSlice withTargetModuleName(String newTargetModuleName) {
        return new CommonModuleSlice(originalModuleName, newTargetModuleName, targetRelativePath, retainedClasses, prunedClasses, included);
    }
}
