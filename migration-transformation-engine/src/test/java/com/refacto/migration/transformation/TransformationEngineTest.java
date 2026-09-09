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

    @Test
    void shouldTransformTryFinallyToTryWithResources() {
        String code = """
                package com.sample;
                import java.io.*;
                public class FileService {
                    public void readFile(File file) throws IOException {
                        FileInputStream fis = null;
                        try {
                            fis = new FileInputStream(file);
                            fis.read();
                        } finally {
                            if (fis != null) {
                                fis.close();
                            }
                        }
                    }
                }
                """;

        Finding finding = Finding.of(
                "F-2", "batch-payment", "JAVA17-001", "1.0",
                Category.JAVA17, Severity.MEDIUM, 95, AutomationLevel.AUTO_SAFE,
                "FileService.java", 5, 13, "try-finally",
                "Conversion try-finally vers try-with-resources", "Clean code",
                "Fix", "Use try-with-resources", 1.0, "Low", code
        );

        AstTransformerService transformer = new AstTransformerService();
        var transformed = transformer.transformCode(code, finding);

        assertThat(transformed).isPresent();
        String result = transformed.get();
        assertThat(result).contains("try (FileInputStream fis = new FileInputStream(file))");
        assertThat(result).contains("fis.read();");
        assertThat(result).doesNotContain("finally");
        assertThat(result).doesNotContain("fis.close()");
    }

    @Test
    void shouldTransformNestedLoopsToStream() {
        String code = """
                package com.sample;
                import java.util.List;
                import java.util.ArrayList;
                public class BatchProcessor {
                    public List<String> process(List<List<String>> groups) {
                        List<String> results = new ArrayList<>();
                        for (List<String> group : groups) {
                            for (String item : group) {
                                if (item.startsWith("OK")) {
                                    results.add(item);
                                }
                            }
                        }
                        return results;
                    }
                }
                """;

        Finding finding = Finding.of(
                "F-3", "batch-payment", "JAVA17-003", "1.0",
                Category.JAVA17, Severity.LOW, 95, AutomationLevel.AUTO_SAFE,
                "BatchProcessor.java", 7, 14, "nested-loop",
                "Boucle imbriquee vers stream", "Clean code",
                "Fix", "Use streams", 1.0, "Low", code
        );

        AstTransformerService transformer = new AstTransformerService();
        var transformed = transformer.transformCode(code, finding);

        assertThat(transformed).isPresent();
        String result = transformed.get();
        assertThat(result).contains(".stream()");
        assertThat(result).contains(".flatMap(");
        assertThat(result).contains(".filter(");
        assertThat(result).contains(".toList()");
    }

    @Test
    void shouldIgnoreWhitespaceOnlyDiffs() {
        DiffGeneratorService diffGen = new DiffGeneratorService();

        String original = "public class A {\n    int x = 1;\n}\n";
        String sameCodeDifferentSpacing = "public class A {\n\n        int x = 1;\n\n}\n";
        String realChange = "public class A {\n    int x = 2;\n}\n";

        assertThat(diffGen.hasMeaningfulChanges(original, sameCodeDifferentSpacing)).isFalse();
        assertThat(diffGen.hasMeaningfulChanges(original, realChange)).isTrue();
    }

    @Test
    void shouldTransformBatch5Builders() {
        String code = """
                package com.sample.batch;
                import org.springframework.batch.core.Job;
                import org.springframework.batch.core.Step;
                import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
                import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
                public class PaymentJobConfig {
                    private final JobBuilderFactory jobBuilderFactory;
                    private final StepBuilderFactory stepBuilderFactory;
                    public PaymentJobConfig(JobBuilderFactory jobBuilderFactory, StepBuilderFactory stepBuilderFactory) {
                        this.jobBuilderFactory = jobBuilderFactory;
                        this.stepBuilderFactory = stepBuilderFactory;
                    }
                    public Job paymentJob() {
                        return jobBuilderFactory.get("paymentJob").build();
                    }
                    public Step paymentStep() {
                        return stepBuilderFactory.get("paymentStep").build();
                    }
                }
                """;

        Finding finding = Finding.of(
                "F-4", "batch-payment", "APP-BATCH-001", "1.0",
                Category.FRAMEWORK, Severity.HIGH, 98, AutomationLevel.AUTO_SAFE,
                "PaymentJobConfig.java", 6, 12, "JobBuilderFactory",
                "Migration Batch 5", "Batch 5 builders",
                "Fix", "Use new JobBuilder", 2.0, "Medium", code
        );

        AstTransformerService transformer = new AstTransformerService();
        var transformed = transformer.transformCode(code, finding);

        assertThat(transformed).isPresent();
        String result = transformed.get();
        assertThat(result).contains("new JobBuilder(\"paymentJob\", jobRepository)");
        assertThat(result).contains("new StepBuilder(\"paymentStep\", jobRepository)");
        assertThat(result).contains("JobRepository jobRepository");
        assertThat(result).doesNotContain("JobBuilderFactory");
        assertThat(result).doesNotContain("StepBuilderFactory");
    }

    @Test
    void shouldTransformFileToPath() {
        String code = """
                package com.sample;
                import java.io.File;
                public class AuditService {
                    public void checkFile() {
                        File auditFile = new File("/tmp/payment.log");
                        if (auditFile.exists()) {
                            System.out.println("exists");
                        }
                    }
                }
                """;

        Finding finding = Finding.of(
                "F-5", "batch-payment", "JAVA17-002", "1.0",
                Category.JAVA17, Severity.LOW, 95, AutomationLevel.AUTO_SAFE,
                "AuditService.java", 5, 8, "new File",
                "Migration File vers Path", "NIO modernize",
                "Fix", "Use Path.of", 0.5, "Low", code
        );

        AstTransformerService transformer = new AstTransformerService();
        var transformed = transformer.transformCode(code, finding);

        assertThat(transformed).isPresent();
        String result = transformed.get();
        assertThat(result).contains("Path auditFile = Path.of(\"/tmp/payment.log\")");
        assertThat(result).contains("Files.exists(auditFile)");
        assertThat(result).contains("import java.nio.file.Path;");
        assertThat(result).contains("import java.nio.file.Files;");
    }

    @Test
    void shouldTransformSwitchExpressions() {
        String code = """
                package com.sample;
                public class StatusHelper {
                    public int getCode(String status) {
                        switch (status) {
                            case "ACTIVE":
                                return 1;
                            case "PENDING":
                                return 2;
                            default:
                                return 0;
                        }
                    }
                }
                """;

        Finding finding = Finding.of(
                "F-6", "batch-payment", "JAVA17-004", "1.0",
                Category.JAVA17, Severity.LOW, 90, AutomationLevel.AUTO_SAFE,
                "StatusHelper.java", 4, 10, "switch",
                "Switch expressions", "Clean code",
                "Fix", "Use ->", 0.5, "Low", code
        );

        AstTransformerService transformer = new AstTransformerService();
        var transformed = transformer.transformCode(code, finding);

        assertThat(transformed).isPresent();
        String result = transformed.get();
        assertThat(result).contains("case \"ACTIVE\" ->");
        assertThat(result).contains("case \"PENDING\" ->");
        assertThat(result).contains("default ->");
        assertThat(result).doesNotContain("case \"ACTIVE\":");
    }
}
