package com.refacto.migration.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.refacto.migration.core.enums.AutomationLevel;
import com.refacto.migration.core.enums.Category;
import com.refacto.migration.core.enums.Severity;
import com.refacto.migration.core.enums.ValidationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreModelTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shouldSerializeAndDeserializeFinding() throws Exception {
        Finding finding = Finding.of(
                "APP-DB-004-1",
                "batch-payment",
                "APP-DB-004",
                "1.0",
                Category.DATABASE,
                Severity.HIGH,
                95,
                AutomationLevel.REVIEW_REQUIRED,
                "src/main/java/com/sample/PaymentProcessor.java",
                143,
                145,
                "dao.find",
                "Database access inside loop",
                "N+1 queries",
                "High latency under volume",
                "Use batch retrieval",
                4.0,
                "Medium",
                "for (Item i : items) { dao.find(i.getId()); }"
        );

        String json = mapper.writeValueAsString(finding);
        Finding deserialized = mapper.readValue(json, Finding.class);

        assertThat(deserialized.id()).isEqualTo("APP-DB-004-1");
        assertThat(deserialized.severity()).isEqualTo(Severity.HIGH);
        assertThat(deserialized.automationLevel()).isEqualTo(AutomationLevel.REVIEW_REQUIRED);
        assertThat(deserialized.confidence()).isEqualTo(95);
    }

    @Test
    void shouldCalculatePerformanceGainCorrectly() {
        PerformanceComparison comparison = PerformanceComparison.compare(
                "PaymentBatch",
                850_000,
                13 * 60 * 1000 + 42 * 1000, // 13m42s = 822000 ms
                7 * 60 * 1000 + 16 * 1000,  // 7m16s = 436000 ms
                "checksum-abc-123",
                "checksum-abc-123"
        );

        assertThat(comparison.gainPercent()).isEqualTo(46.96, org.assertj.core.data.Offset.offset(0.1));
        assertThat(comparison.performanceStatus()).isEqualTo(ValidationStatus.PASS);
        assertThat(comparison.functionalStatus()).isEqualTo(ValidationStatus.PASS);
        assertThat(comparison.resultValidated()).isTrue();
    }
}
