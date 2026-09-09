package com.refacto.migration.ai;

import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.FindingStatus;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.model.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAssistantServiceTest {

    @Test
    void shouldExplainFindingInOfflineMode() {
        AiAssistantService assistant = new AiAssistantService();

        Finding finding = new Finding(
                "f1", "proj", "an1", "batch-payment", "APP-DB-001", "1.0",
                Category.DATABASE, Severity.HIGH, 95, AutomationLevel.REVIEW_REQUIRED,
                "PaymentProcessor.java", 20, 25, "entityManager",
                "Accès direct DB", "Hors DAO", "Couplage fort",
                "Créer DAO", 4.0, "Medium", FindingStatus.OPEN,
                "entityManager.persist(x);", null, null
        );

        String explanation = assistant.explainFinding(finding);
        assertThat(explanation).contains("APP-DB-001").contains("DAO");

        String refactored = assistant.suggestRefactoring(finding, "entityManager.persist(x);");
        assertThat(refactored).contains("@Repository").contains("PaymentDAO");
    }

    @Test
    void shouldAnswerUserPromptInOfflineMode() {
        AiAssistantService assistant = new AiAssistantService();

        String responseDdd = assistant.askAssistant("Comment faire une migration DDD ?", null, List.of());
        assertThat(responseDdd).contains("Bounded Contexts").contains("Domain");

        String responseBatch = assistant.askAssistant("Quels sont les impacts Spring Batch 5 ?", null, List.of());
        assertThat(responseBatch).contains("JobBuilderFactory").contains("JobRepository");
    }

    @Test
    void shouldHandleConfigUpdates() {
        AiAssistantService assistant = new AiAssistantService();
        assertThat(assistant.getConfig().enabled()).isFalse();

        LlmConfig ollamaConfig = LlmConfig.defaultOllama();
        assistant.updateConfig(ollamaConfig);

        assertThat(assistant.getConfig().provider()).isEqualTo("ollama");
        assertThat(assistant.getConfig().enabled()).isTrue();
    }
}
