package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Représente un module enfant référencé par un packaging, déplacé à l'intérieur du sous-projet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModuleReorganizationItem(
        String moduleArtifactId,
        String originalRelativePath,
        String targetRelativePath,
        boolean included
) {
    public ModuleReorganizationItem withIncluded(boolean newIncluded) {
        return new ModuleReorganizationItem(moduleArtifactId, originalRelativePath, targetRelativePath, newIncluded);
    }

    public ModuleReorganizationItem withTargetRelativePath(String newTargetRelativePath) {
        return new ModuleReorganizationItem(moduleArtifactId, originalRelativePath, newTargetRelativePath, included);
    }
}
