package com.refacto.migration.code;

import com.refacto.migration.core.enums.CouplingLevel;
import com.refacto.migration.core.model.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DddRefactoringServiceTest {

    @Test
    void shouldGenerateDddRefactoringPlanOnSampleApp() {
        Path sampleApp = Paths.get("d:/work/sample/refacto/sample-legacy-app");
        if (!sampleApp.toFile().exists()) {
            return;
        }

        List<ModuleDescriptor> modules = List.of(
                new ModuleDescriptor("root", "sample-legacy-app", ".", "com.sample", "sample-legacy-app", "1.0", "pom", "1.8", java.util.Set.of(), List.of(), List.of(), 0, 0, false, false),
                new ModuleDescriptor("mod-1", "batch-payment", "batch-payment", "com.sample", "batch-payment", "1.0", "jar", "1.8", java.util.Set.of("spring-batch"), List.of(), List.of(), 3, 0, true, false),
                new ModuleDescriptor("mod-2", "payment-model", "payment-model", "com.sample", "payment-model", "1.0", "jar", "1.8", java.util.Set.of("jpa"), List.of(), List.of(), 1, 0, false, true),
                new ModuleDescriptor("mod-3", "legacy-common", "legacy-common", "com.sample", "legacy-common", "1.0", "jar", "1.8", java.util.Set.of(), List.of(), List.of(), 2, 0, false, false)
        );

        List<BatchDescriptor> batches = List.of(
                new BatchDescriptor("paymentJob", "PaymentJobConfig", "batch-payment/config.xml", "batch-payment", List.of(), List.of(), null)
        );

        List<ArchitectureCoupling> couplings = List.of(
                new ArchitectureCoupling("SharedCustomerDAO", "DAO", "legacy-common", List.of("paymentJob"), List.of("batch-payment"), CouplingLevel.HIGH, false, "Scinder"),
                new ArchitectureCoupling("StringUtilsHelper", "TECHNICAL_UTILITY", "legacy-common", List.of(), List.of("batch-payment"), CouplingLevel.LOW, true, "Commons")
        );

        DddRefactoringService service = new DddRefactoringService();
        DddRefactoringPlan plan = service.buildRefactoringPlan(sampleApp, modules, batches, couplings);

        assertThat(plan).isNotNull();
        assertThat(plan.boundedContexts()).isNotEmpty();

        // Must contain payment-context and app-commons
        assertThat(plan.boundedContexts()).anyMatch(c -> c.id().equals("payment-context"));
        assertThat(plan.boundedContexts()).anyMatch(c -> c.id().equals("app-commons"));

        // Must classify classes by DDD layer
        assertThat(plan.classifications()).isNotEmpty();
        assertThat(plan.classifications()).anyMatch(c -> c.className().equals("PaymentProcessor") && c.layer() == DddLayer.APPLICATION);
        assertThat(plan.classifications()).anyMatch(c -> c.className().equals("StringUtilsHelper") && c.layer() == DddLayer.COMMONS);

        // Must produce target module structure
        assertThat(plan.targetModules()).isNotEmpty();
        assertThat(plan.targetModules()).anyMatch(m -> m.moduleArtifactId().equals("payment-domain"));
        assertThat(plan.targetModules()).anyMatch(m -> m.moduleArtifactId().equals("payment-application"));
        assertThat(plan.targetModules()).anyMatch(m -> m.moduleArtifactId().equals("payment-infrastructure"));
        assertThat(plan.targetModules()).anyMatch(m -> m.moduleArtifactId().equals("app-commons"));

        // Decoupling metrics
        assertThat(plan.decoupledEntitiesCount()).isGreaterThan(0);
        assertThat(plan.executiveSummary()).contains("Bounded Contexts");
    }
}
