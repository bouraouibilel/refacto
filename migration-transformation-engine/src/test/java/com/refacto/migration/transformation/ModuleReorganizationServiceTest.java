package com.refacto.migration.transformation;

import com.refacto.migration.core.model.ModuleDescriptor;
import com.refacto.migration.core.model.ModuleReorganizationItem;
import com.refacto.migration.core.model.ModuleReorganizationPlan;
import com.refacto.migration.core.model.PackagingSubProjectGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleReorganizationServiceTest {

    private final ModuleReorganizationService service = new ModuleReorganizationService();

    @Test
    void shouldDetectPackagingAndChildModules(@TempDir Path tempDir) throws IOException {
        // Given root pom.xml
        String rootPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent-app</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>legacy-common</module>
                        <module>payment-model</module>
                        <module>batch-payment</module>
                        <module>packaging-batch-payment</module>
                    </modules>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), rootPom);

        // Child 1: legacy-common
        Path commonDir = tempDir.resolve("legacy-common");
        Files.createDirectories(commonDir);
        Files.writeString(commonDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>legacy-common</artifactId></project>
                """);

        // Child 2: payment-model
        Path modelDir = tempDir.resolve("payment-model");
        Files.createDirectories(modelDir);
        Files.writeString(modelDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>payment-model</artifactId></project>
                """);

        // Child 3: batch-payment
        Path batchDir = tempDir.resolve("batch-payment");
        Files.createDirectories(batchDir);
        Files.writeString(batchDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>batch-payment</artifactId></project>
                """);

        // Packaging: packaging-batch-payment (dépends on payment-model & batch-payment)
        Path pkgDir = tempDir.resolve("packaging-batch-payment");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>packaging-batch-payment</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>payment-model</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>batch-payment</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        // When
        ModuleReorganizationPlan plan = service.analyzeReorganization(tempDir, List.of());

        // Then
        assertThat(plan.groups()).hasSize(1);
        PackagingSubProjectGroup group = plan.groups().get(0);
        assertThat(group.packagingArtifactId()).isEqualTo("packaging-batch-payment");
        assertThat(group.originalPackagingDir()).isEqualTo("packaging-batch-payment");
        assertThat(group.targetSubProjectDir()).isEqualTo("batch-payment-app");
        assertThat(group.childModules()).hasSize(2);
        assertThat(group.childModules().stream().map(ModuleReorganizationItem::moduleArtifactId))
                .containsExactlyInAnyOrder("payment-model", "batch-payment");

        assertThat(plan.unassignedModules()).contains("legacy-common");
        assertThat(plan.rootPomDiff()).contains("+        <module>batch-payment-app</module>");
        assertThat(plan.subProjectPomDiffs()).containsKey("batch-payment-app/pom.xml");
        assertThat(plan.gitMoveCommands()).isNotEmpty();

    }

    @Test
    void shouldApplyReorganizationPhysically(@TempDir Path tempDir) throws IOException {
        // Setup project
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <modules>
                        <module>common</module>
                        <module>payment-service</module>
                        <module>packaging-payment</module>
                    </modules>
                </project>
                """);

        Path common = tempDir.resolve("common");
        Files.createDirectories(common);
        Files.writeString(common.resolve("pom.xml"), "<project><artifactId>common</artifactId></project>");

        Path serviceDir = tempDir.resolve("payment-service");
        Files.createDirectories(serviceDir);
        Files.writeString(serviceDir.resolve("pom.xml"), "<project><artifactId>payment-service</artifactId></project>");

        Path pkg = tempDir.resolve("packaging-payment");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("pom.xml"), """
                <project>
                    <artifactId>packaging-payment</artifactId>
                    <dependencies>
                        <dependency><artifactId>payment-service</artifactId></dependency>
                    </dependencies>
                </project>
                """);

        ModuleReorganizationPlan plan = service.analyzeReorganization(tempDir, List.of());
        assertThat(plan.groups()).hasSize(1);

        // Execute
        ModuleReorganizationPlan applied = service.applyReorganization(tempDir, plan);
        assertThat(applied.applied()).isTrue();

        // Verify filesystem
        Path targetSubDir = tempDir.resolve("payment");
        assertThat(targetSubDir).exists().isDirectory();
        assertThat(targetSubDir.resolve("pom.xml")).exists();
        assertThat(Files.readString(targetSubDir.resolve("pom.xml"))).contains("<module>payment-service</module>");

        // Child moved inside targetSubDir
        assertThat(targetSubDir.resolve("payment-service")).exists();
        assertThat(targetSubDir.resolve("payment-service").resolve("pom.xml")).exists();

        // Old directories removed from root
        assertThat(tempDir.resolve("packaging-payment")).doesNotExist();
        assertThat(tempDir.resolve("payment-service")).doesNotExist();

        // Common still at root
        assertThat(tempDir.resolve("common")).exists();

        // Root pom updated
        String rootPomContent = Files.readString(tempDir.resolve("pom.xml"));
        assertThat(rootPomContent).contains("<module>payment</module>");
        assertThat(rootPomContent).doesNotContain("<module>packaging-payment</module>");
        assertThat(rootPomContent).doesNotContain("<module>payment-service</module>");
    }

    @Test
    void shouldSupportDynamicGroupCreationAndModuleReassignment(@TempDir Path tempDir) throws IOException {
        // Setup base project with 2 modules
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                    <groupId>com.sample</groupId>
                    <artifactId>sample-app</artifactId>
                    <version>1.0.0</version>
                    <modules>
                        <module>module-a</module>
                        <module>module-b</module>
                    </modules>
                </project>
                """);

        Path modA = tempDir.resolve("module-a");
        Files.createDirectories(modA);
        Files.writeString(modA.resolve("pom.xml"), "<project><artifactId>module-a</artifactId></project>");

        Path modB = tempDir.resolve("module-b");
        Files.createDirectories(modB);
        Files.writeString(modB.resolve("pom.xml"), "<project><artifactId>module-b</artifactId></project>");

        // Manually create a new group "custom-suite" containing "module-a", leaving "module-b" as unassigned
        PackagingSubProjectGroup newGroup = new PackagingSubProjectGroup(
                "custom-suite-pack",
                "custom-suite",
                "custom-suite",
                List.of(new ModuleReorganizationItem("module-a", "module-a", "custom-suite/module-a", true)),
                true,
                "MANUAL"
        );

        ModuleReorganizationPlan customPlan = new ModuleReorganizationPlan(
                "pom.xml",
                List.of(newGroup),
                List.of("module-b"),
                "",
                java.util.Map.of(),
                List.of(),
                false,
                "Test custom plan"
        );

        // Preview
        ModuleReorganizationPlan preview = service.previewCustomizedPlan(tempDir, customPlan);
        assertThat(preview.rootPomDiff()).contains("+        <module>custom-suite</module>");
        assertThat(preview.rootPomDiff()).contains("<module>module-b</module>");
        assertThat(preview.subProjectPomDiffs()).containsKey("custom-suite/pom.xml");

        // Apply
        ModuleReorganizationPlan applied = service.applyReorganization(tempDir, preview);
        assertThat(applied.applied()).isTrue();

        // Check file system
        assertThat(tempDir.resolve("custom-suite")).exists().isDirectory();
        assertThat(tempDir.resolve("custom-suite").resolve("pom.xml")).exists();
        String subPom = Files.readString(tempDir.resolve("custom-suite").resolve("pom.xml"));
        assertThat(subPom).contains("<artifactId>custom-suite-pack</artifactId>");
        assertThat(subPom).contains("<module>module-a</module>");

        // module-a is moved inside custom-suite
        assertThat(tempDir.resolve("custom-suite").resolve("module-a")).exists();
        assertThat(tempDir.resolve("module-a")).doesNotExist();

        // module-b remained at root
        assertThat(tempDir.resolve("module-b")).exists();

        // root pom has both custom-suite and module-b
        String finalRootPom = Files.readString(tempDir.resolve("pom.xml"));
        assertThat(finalRootPom).contains("<module>custom-suite</module>");
        assertThat(finalRootPom).contains("<module>module-b</module>");
        assertThat(finalRootPom).doesNotContain("<module>module-a</module>");
    }

    @Test
    void shouldSliceAndPruneCommonModulesWhenApplied(@TempDir Path tempDir) throws IOException {
        // Given root pom.xml
        String rootPom = """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample</groupId>
                    <artifactId>sample-root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>legacy-common</module>
                        <module>batch-payment</module>
                        <module>packaging-batch-payment</module>
                    </modules>
                </project>
                """;
        Files.writeString(tempDir.resolve("pom.xml"), rootPom);

        // legacy-common with 2 classes
        Path commonDir = tempDir.resolve("legacy-common");
        Path commonSrc = commonDir.resolve("src/main/java/com/sample/common");
        Files.createDirectories(commonSrc);
        Files.writeString(commonDir.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.sample</groupId>
                        <artifactId>sample-root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>legacy-common</artifactId>
                </project>
                """);
        Files.writeString(commonSrc.resolve("UsedService.java"), """
                package com.sample.common;
                public class UsedService {}
                """);
        Files.writeString(commonSrc.resolve("UnusedService.java"), """
                package com.sample.common;
                public class UnusedService {}
                """);

        // batch-payment using UsedService
        Path batchDir = tempDir.resolve("batch-payment");
        Path batchSrc = batchDir.resolve("src/main/java/com/sample/batch");
        Files.createDirectories(batchSrc);
        Files.writeString(batchDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>batch-payment</artifactId></project>
                """);
        Files.writeString(batchSrc.resolve("BatchWorker.java"), """
                package com.sample.batch;
                import com.sample.common.UsedService;
                public class BatchWorker {
                    private UsedService s;
                }
                """);

        // packaging-batch-payment
        Path pkgDir = tempDir.resolve("packaging-batch-payment");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>packaging-batch-payment</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.sample</groupId>
                            <artifactId>batch-payment</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        // When analyzing
        ModuleReorganizationPlan plan = service.analyzeReorganization(tempDir, List.of());

        // Then group has commonModuleSlices
        assertThat(plan.groups()).hasSize(1);
        PackagingSubProjectGroup group = plan.groups().get(0);
        assertThat(group.commonModuleSlices()).hasSize(1);
        var slice = group.commonModuleSlices().get(0);
        assertThat(slice.originalModuleName()).isEqualTo("legacy-common");
        assertThat(slice.targetModuleName()).isEqualTo("legacy-common");
        assertThat(slice.retainedClasses()).anyMatch(c -> c.contains("UsedService.java"));
        assertThat(slice.prunedClasses()).anyMatch(c -> c.contains("UnusedService.java"));

        // When applying reorganization
        ModuleReorganizationPlan applied = service.applyReorganization(tempDir, plan);
        assertThat(applied.applied()).isTrue();

        // Check target subproject
        Path targetSubDir = tempDir.resolve("batch-payment-app");
        assertThat(targetSubDir).exists().isDirectory();
        Path subPom = targetSubDir.resolve("pom.xml");
        assertThat(subPom).exists();
        String subPomContent = Files.readString(subPom);
        assertThat(subPomContent).contains("<module>batch-payment</module>");
        assertThat(subPomContent).contains("<module>legacy-common</module>");

        // Check common module inside subproject
        Path targetCommon = targetSubDir.resolve("legacy-common");
        assertThat(targetCommon).exists().isDirectory();
        assertThat(targetCommon.resolve("pom.xml")).exists();

        // Retained class exists, pruned class is deleted
        assertThat(targetCommon.resolve("src/main/java/com/sample/common/UsedService.java")).exists();
        assertThat(targetCommon.resolve("src/main/java/com/sample/common/UnusedService.java")).doesNotExist();
    }
}
