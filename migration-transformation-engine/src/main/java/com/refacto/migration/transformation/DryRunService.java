package com.refacto.migration.transformation;

import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.ChangeStatus;
import com.refacto.migration.core.model.DiffEntry;
import com.refacto.migration.core.model.DryRunResult;
import com.refacto.migration.core.model.Finding;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Moteur de Dry-Run (Section 39) : exécute les transformations en mémoire sans modifier le repository.
 */
public class DryRunService {

    private static final Logger log = LoggerFactory.getLogger(DryRunService.class);

    private final AstTransformerService astTransformer = new AstTransformerService();
    private final DiffGeneratorService diffGenerator = new DiffGeneratorService();

    public DryRunResult executeDryRun(Path projectRoot, List<Finding> findings, int totalFilesScanned) {
        List<DiffEntry> diffEntries = new ArrayList<>();

        int autoSafe = 0;
        int autoWithTests = 0;
        int reviewRequired = 0;
        int manualOnly = 0;

        for (Finding finding : findings) {
            AutomationLevel level = finding.automationLevel();
            switch (level) {
                case AUTO_SAFE -> autoSafe++;
                case AUTO_WITH_TESTS -> autoWithTests++;
                case REVIEW_REQUIRED -> reviewRequired++;
                case MANUAL_ONLY -> manualOnly++;
            }

            // Generate diff for automatable findings
            if (level != AutomationLevel.MANUAL_ONLY && finding.filePath() != null) {
                Path targetFile = projectRoot.resolve(finding.filePath());
                if (targetFile.toFile().exists()) {
                    try {
                        String originalContent = FileUtils.readFileToString(targetFile.toFile(), StandardCharsets.UTF_8);
                        Optional<String> transformed = astTransformer.transformCode(originalContent, finding);

                        if (transformed.isPresent() && !transformed.get().equals(originalContent)) {
                            String unifiedDiff = diffGenerator.generateUnifiedDiff(
                                    finding.filePath(),
                                    originalContent,
                                    transformed.get()
                            );

                            diffEntries.add(new DiffEntry(
                                    UUID.randomUUID().toString().substring(0, 8),
                                    finding.id(),
                                    finding.recipeId(),
                                    finding.recipeId() + " Transformation",
                                    finding.filePath(),
                                    level,
                                    originalContent,
                                    transformed.get(),
                                    unifiedDiff,
                                    ChangeStatus.PENDING,
                                    null,
                                    finding.description()
                            ));
                        }
                    } catch (Exception e) {
                        log.debug("Erreur dry-run diff pour {}: {}", finding.filePath(), e.getMessage());
                    }
                }
            }
        }

        return new DryRunResult(
                totalFilesScanned,
                findings.size(),
                diffEntries.size(),
                autoSafe,
                autoWithTests,
                reviewRequired,
                manualOnly,
                diffEntries
        );
    }
}
