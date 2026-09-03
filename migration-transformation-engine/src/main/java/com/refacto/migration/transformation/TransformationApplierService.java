package com.refacto.migration.transformation;

import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.ChangeStatus;
import com.refacto.migration.core.model.DiffEntry;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Service d'application et de gestion des changements de code approuvés (Sections 40 et 41).
 */
public class TransformationApplierService {

    private static final Logger log = LoggerFactory.getLogger(TransformationApplierService.class);

    public DiffEntry approveChange(DiffEntry entry) {
        return new DiffEntry(
                entry.id(),
                entry.findingId(),
                entry.recipeId(),
                entry.recipeName(),
                entry.filePath(),
                entry.automationLevel(),
                entry.originalContent(),
                entry.transformedContent(),
                entry.unifiedDiff(),
                ChangeStatus.APPROVED,
                null,
                entry.explanation()
        );
    }

    public DiffEntry rejectChange(DiffEntry entry, String reason) {
        return new DiffEntry(
                entry.id(),
                entry.findingId(),
                entry.recipeId(),
                entry.recipeName(),
                entry.filePath(),
                entry.automationLevel(),
                entry.originalContent(),
                entry.transformedContent(),
                entry.unifiedDiff(),
                ChangeStatus.REJECTED,
                reason,
                entry.explanation()
        );
    }

    public List<DiffEntry> applyApprovedChanges(Path projectRoot, List<DiffEntry> diffs, boolean applyAutoSafe) {
        List<DiffEntry> updatedDiffs = new ArrayList<>();

        for (DiffEntry diff : diffs) {
            boolean shouldApply = (diff.status() == ChangeStatus.APPROVED) ||
                    (applyAutoSafe && diff.automationLevel() == AutomationLevel.AUTO_SAFE && diff.status() != ChangeStatus.REJECTED);

            if (shouldApply) {
                try {
                    Path file = projectRoot.resolve(diff.filePath());
                    FileUtils.writeStringToFile(file.toFile(), diff.transformedContent(), StandardCharsets.UTF_8);
                    log.info("Changement appliqué avec succès sur {}", diff.filePath());

                    updatedDiffs.add(new DiffEntry(
                            diff.id(),
                            diff.findingId(),
                            diff.recipeId(),
                            diff.recipeName(),
                            diff.filePath(),
                            diff.automationLevel(),
                            diff.originalContent(),
                            diff.transformedContent(),
                            diff.unifiedDiff(),
                            ChangeStatus.APPLIED,
                            null,
                            diff.explanation()
                    ));
                } catch (Exception e) {
                    log.error("Erreur écriture fichier {}: {}", diff.filePath(), e.getMessage());
                    updatedDiffs.add(diff);
                }
            } else {
                updatedDiffs.add(diff);
            }
        }

        return updatedDiffs;
    }
}
