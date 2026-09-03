package com.refacto.migration.dependency;

import com.refacto.migration.core.model.Dependency;
import com.refacto.migration.core.model.ModuleDescriptor;
import com.refacto.migration.core.model.TargetProfile;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyAnalyzerTest {

    @Test
    void shouldBuildInventoryAndDetectConflictsAndBreakingChanges() {
        ModuleDescriptor moduleA = new ModuleDescriptor(
                "mod-a", "mod-a", "mod-a", "com.sample", "mod-a", "1.0", "jar", "11",
                Set.of("Spring Batch"),
                List.of(
                        "org.springframework.batch:spring-batch-core:4.3.0",
                        "junit:junit:4.12"
                ),
                Collections.emptyList(), 10, 5, true, false
        );

        ModuleDescriptor moduleB = new ModuleDescriptor(
                "mod-b", "mod-b", "mod-b", "com.sample", "mod-b", "1.0", "jar", "11",
                Set.of("Spring Batch"),
                List.of(
                        "org.springframework.batch:spring-batch-core:4.2.0", // Conflict with mod-a!
                        "junit:junit:4.12"
                ),
                Collections.emptyList(), 15, 8, true, false
        );

        DependencyAnalyzerService service = new DependencyAnalyzerService();
        TargetProfile targetProfile = TargetProfile.defaultJava17Profile();

        List<Dependency> inventory = service.buildInventory(List.of(moduleA, moduleB), targetProfile);

        assertThat(inventory).isNotEmpty();

        Dependency batchDep = inventory.stream()
                .filter(d -> d.artifactId().equals("spring-batch-core"))
                .findFirst().orElseThrow();

        assertThat(batchDep.conflict()).isTrue();
        assertThat(batchDep.currentVersion()).contains("4.3.0").contains("4.2.0");
        assertThat(batchDep.targetVersion()).isEqualTo("5.x");
        assertThat(batchDep.breakingChangeCount()).isGreaterThan(0);
        assertThat(batchDep.migrationStatus()).isEqualTo("BREAKING_MIGRATION");
        assertThat(batchDep.modules()).containsExactlyInAnyOrder("mod-a", "mod-b");
    }
}
