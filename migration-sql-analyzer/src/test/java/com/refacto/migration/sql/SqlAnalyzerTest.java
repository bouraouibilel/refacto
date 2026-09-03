package com.refacto.migration.sql;

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

class SqlAnalyzerTest {

    @Test
    void shouldDetectDbAccessOutsideDaoAndSelectForUpdate(@TempDir Path tempDir) throws Exception {
        Path javaDir = tempDir.resolve("src/main/java/com/sample");
        javaDir.toFile().mkdirs();

        String code = """
                package com.sample;
                import javax.persistence.EntityManager;
                import java.util.List;

                public class PaymentBatchProcessor {
                    private EntityManager entityManager;

                    public void process(List<Long> ids) {
                        String query = "SELECT * FROM payment p WHERE p.status = 'PENDING' FOR UPDATE";
                        // DB in loop
                        for (Long id : ids) {
                            entityManager.find(Payment.class, id);
                        }
                    }
                }
                """;
        FileUtils.writeStringToFile(javaDir.resolve("PaymentBatchProcessor.java").toFile(), code, StandardCharsets.UTF_8);

        SqlAnalyzerService service = new SqlAnalyzerService();
        List<Finding> findings = service.analyzeModule(tempDir, "batch-payment", tempDir);

        assertThat(findings).isNotEmpty();

        // Check APP-DB-001 (DB access outside DAO)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-001") && f.category() == Category.DATABASE);

        // Check APP-DB-003 (SELECT FOR UPDATE)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-003") && f.automationLevel() == AutomationLevel.MANUAL_ONLY);

        // Check APP-DB-004 (DB in loop)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-004") && f.potentialRoundTrips() != null);

        // Check APP-DB-005 (SELECT *)
        assertThat(findings).anyMatch(f -> f.recipeId().equals("APP-DB-005"));
    }
}
