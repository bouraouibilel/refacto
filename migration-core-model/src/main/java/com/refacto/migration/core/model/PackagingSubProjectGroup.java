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
        List<CommonModuleSlice> commonModuleSlices,
        boolean included,
        String status
) {
    public PackagingSubProjectGroup {
        if (childModules == null) childModules = List.of();
        if (commonModuleSlices == null) commonModuleSlices = List.of();
    }

    public PackagingSubProjectGroup(
            String packagingArtifactId,
            String originalPackagingDir,
            String targetSubProjectDir,
            List<ModuleReorganizationItem> childModules,
            boolean included,
            String status
    ) {
        this(packagingArtifactId, originalPackagingDir, targetSubProjectDir, childModules, List.of(), included, status);
    }

    public PackagingSubProjectGroup withPackagingArtifactId(String newArtifactId) {
        return new PackagingSubProjectGroup(newArtifactId, originalPackagingDir, targetSubProjectDir, childModules, commonModuleSlices, included, status);
    }

    public PackagingSubProjectGroup withTargetSubProjectDir(String newTargetDir) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, newTargetDir, childModules, commonModuleSlices, included, status);
    }

    public PackagingSubProjectGroup withChildModules(List<ModuleReorganizationItem> newChildModules) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, targetSubProjectDir, newChildModules, commonModuleSlices, included, status);
    }

    public PackagingSubProjectGroup withCommonModuleSlices(List<CommonModuleSlice> newSlices) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, targetSubProjectDir, childModules, newSlices, included, status);
    }

    public PackagingSubProjectGroup withIncluded(boolean newIncluded) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, targetSubProjectDir, childModules, commonModuleSlices, newIncluded, status);
    }

    public PackagingSubProjectGroup withStatus(String newStatus) {
        return new PackagingSubProjectGroup(packagingArtifactId, originalPackagingDir, targetSubProjectDir, childModules, commonModuleSlices, included, newStatus);
    }
}
