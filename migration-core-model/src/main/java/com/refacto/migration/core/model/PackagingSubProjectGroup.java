package com.refacto.migration.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Représente un groupe de sous-projet hiérarchique issu d'un module de packaging.
 * L'ancien packaging devient la racine du sous-projet et tous ses modules enfants référencés y sont déplacés.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackagingSubProjectGroup(
        String packagingArtifactId,
        String originalPackagingDir,
        String targetSubProjectDir,
        List<ModuleReorganizationItem> childModules,
        boolean included,
        String status
) {
    public PackagingSubProjectGroup withTargetSubProjectDir(String newTargetDir) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, newTargetDir, childModules, included, status);
    }

    public PackagingSubProjectGroup withChildModules(List<ModuleReorganizationItem> newChildModules) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, targetSubProjectDir, newChildModules, included, status);
    }

    public PackagingSubProjectGroup withIncluded(boolean newIncluded) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, targetSubProjectDir, childModules, newIncluded, status);
    }

    public PackagingSubProjectGroup withStatus(String newStatus) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, targetSubProjectDir, childModules, included, newStatus);
    }
}
