package com.refacto.migration.code;

import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.model.Finding;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAnalyzerTest {

    @Test
    void shouldDetectSingleResponsibilityViolation(@TempDir Path tempDir) throws Exception {
        Path javaDir = tempDir.resolve("src/main/java/com/sample");
        javaDir.toFile().mkdirs();

        String code = """
                package com.sample;
                public class PaymentProcessor {
                    private PaymentDAO paymentDAO;
                    private PaymentValidator validator;
                    private CsvFileWriter csvWriter;
                    private MailNotificationService mailService;

                    public void processPayment() {
                        // validation
                        validator.validate();
                        // dao
                        paymentDAO.save();
                        // file
                        csvWriter.exportCsv();
                        // notify
                        mailService.sendMail();
                    }
                }
                """;
        FileUtils.writeStringToFile(javaDir.resolve("PaymentProcessor.java").toFile(), code, StandardCharsets.UTF_8);

        CodeAnalyzerService service = new CodeAnalyzerService();
        List<Finding> findings = service.analyzeModule(tempDir, "batch-payment", tempDir);

        assertThat(findings).isNotEmpty();
        Finding srpFinding = findings.stream()
                .filter(f -> f.recipeId().equals("APP-CODE-001"))
                .findFirst().orElseThrow();

        assertThat(srpFinding.automationLevel()).isEqualTo(AutomationLevel.MANUAL_ONLY);
        assertThat(srpFinding.category()).isEqualTo(Category.CODE);
        assertThat(srpFinding.suggestedAction()).contains("PaymentDAO").contains("PaymentValidator");
    }

    @Test
    void shouldDetectTryWithResourcesCandidate(@TempDir Path tempDir) throws Exception {
        Path javaDir = tempDir.resolve("src/main/java/com/sample");
        javaDir.toFile().mkdirs();

        String code = """
                package com.sample;
                import java.io.FileInputStream;
                import java.io.IOException;

                public class LegacyFileReader {
                    public void readFile() throws IOException {
                        FileInputStream fis = null;
                        try {
                            fis = new FileInputStream("test.txt");
                            fis.read();
                        } finally {
                            if (fis != null) {
                                fis.close();
                            }
                        }
                    }
                }
                """;
        FileUtils.writeStringToFile(javaDir.resolve("LegacyFileReader.java").toFile(), code, StandardCharsets.UTF_8);

        CodeAnalyzerService service = new CodeAnalyzerService();
        List<Finding> findings = service.analyzeModule(tempDir, "batch-payment", tempDir);

        assertThat(findings).anyMatch(f -> f.recipeId().equals("JAVA17-001") && f.automationLevel() == AutomationLevel.AUTO_SAFE);
    }

    @Test
    void shouldDetectNestedLoops(@TempDir Path tempDir) throws Exception {
        Path javaDir = tempDir.resolve("src/main/java/com/sample");
        javaDir.toFile().mkdirs();

        String code = """
                package com.sample;
                import java.util.List;
                import java.util.ArrayList;

                public class LoopTest {
                    public List<String> process(List<List<String>> matrix) {
                        List<String> res = new ArrayList<>();
                        for (List<String> row : matrix) {
                            for (String cell : row) {
                                res.add(cell);
                            }
                        }
                        return res;
                    }
                }
                """;
        FileUtils.writeStringToFile(javaDir.resolve("LoopTest.java").toFile(), code, StandardCharsets.UTF_8);

        CodeAnalyzerService service = new CodeAnalyzerService();
        List<Finding> findings = service.analyzeModule(tempDir, "test-mod", tempDir);

        assertThat(findings).anyMatch(f -> f.recipeId().equals("JAVA17-003") && f.category() == Category.JAVA17);
    }

    @Test
    void shouldDetectFindingsInSampleLegacyApp() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("d:/work/sample/refacto/sample-legacy-app");
        if (root.toFile().exists()) {
            CodeAnalyzerService service = new CodeAnalyzerService();
            List<Finding> findings = service.analyzeModule(root, "batch-payment", root.resolve("batch-payment"));
            System.out.println("Findings in batch-payment: " + findings.stream().map(Finding::recipeId).toList());
            assertThat(findings).anyMatch(f -> f.recipeId().equals("JAVA17-003"));
        }
    }
}
