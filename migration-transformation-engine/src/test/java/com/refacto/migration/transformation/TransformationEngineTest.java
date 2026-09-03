package com.refacto.migration.transformation;

import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.ChangeStatus;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.DiffEntry;
import com.refacto.migration.core.model.DryRunResult;
import com.refacto.migration.core.model.Finding;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransformationEngineTest {

    @Test
    void shouldGenerateUnifiedDiffAndDryRun(@TempDir Path tempDir) throws Exception {
        String original = """
                package com.sample;
                import javax.persistence.Entity;
                import javax.persistence.Id;

                @Entity
                public class PaymentEntity {
                    @Id
                    private Long id;
                }
                """;
        Path javaFile = tempDir.resolve("PaymentEntity.java");
        FileUtils.writeStringToFile(javaFile.toFile(), original, StandardCharsets.UTF_8);

        Finding finding = Finding.of(
                "F-1", "mod", "FRAMEWORK-001", "1.0",
                Category.FRAMEWORK, Severity.HIGH, 100, AutomationLevel.AUTO_SAFE,
                "PaymentEntity.java", 2, 3, "javax.persistence",
                "Migrate javax to jakarta", "Spring Boot 3 requirement",
                "Update imports", "Replace with jakarta.persistence",
                0.5, "Low", original
        );

        DryRunService dryRunService = new DryRunService();
        DryRunResult result = dryRunService.executeDryRun(tempDir, List.of(finding), 1);

        assertThat(result.potentialChangesCount()).isEqualTo(1);
        assertThat(result.autoSafeCount()).isEqualTo(1);
        assertThat(result.diffEntries()).hasSize(1);

        DiffEntry diff = result.diffEntries().get(0);
        assertThat(diff.unifiedDiff()).contains("-import javax.persistence.Entity;");
        assertThat(diff.unifiedDiff()).contains("+import jakarta.persistence.Entity;");
        assertThat(diff.status()).isEqualTo(ChangeStatus.PENDING);

        // Test application
        TransformationApplierService applier = new TransformationApplierService();
        List<DiffEntry> applied = applier.applyApprovedChanges(tempDir, List.of(diff), true);

        assertThat(applied.get(0).status()).isEqualTo(ChangeStatus.APPLIED);
        String updatedFileContent = FileUtils.readFileToString(javaFile.toFile(), StandardCharsets.UTF_8);
        assertThat(updatedFileContent).contains("jakarta.persistence.Entity");
        assertThat(updatedFileContent).doesNotContain("javax.persistence.Entity");
    }
}
