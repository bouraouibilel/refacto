package com.refacto.migration.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.ModuleReorganizationItem;
import com.refacto.migration.core.model.ModuleReorganizationPlan;
import com.refacto.migration.core.model.PackagingSubProjectGroup;
import com.refacto.migration.core.model.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ModuleReorganizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MigrationOrchestratorService orchestrator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldVerifyRecipeAndFullReorganizationLifecycle(@TempDir Path tempDir) throws Exception {
        // 1. Vérifier que la Recipe APP-ARCH-003 est bien déclarée dans le catalogue
        mockMvc.perform(get("/api/recipes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'APP-ARCH-003')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'APP-ARCH-003')].automationLevel").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$[?(@.id == 'APP-ARCH-003')].recipeClassName").value("com.app.rewrite.ReorganisePackaging"));

        // 2. Créer un projet de test avec un packaging et ses modules enfants
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample.test</groupId>
                    <artifactId>test-parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>common-utils</module>
                        <module>payment-service</module>
                        <module>packaging-payment</module>
                    </modules>
                </project>
                """);

        Path commonDir = tempDir.resolve("common-utils");
        Files.createDirectories(commonDir);
        Files.writeString(commonDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>common-utils</artifactId></project>
                """);

        Path serviceDir = tempDir.resolve("payment-service");
        Files.createDirectories(serviceDir);
        Files.writeString(serviceDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><artifactId>payment-service</artifactId></project>
                """);

        Path pkgDir = tempDir.resolve("packaging-payment");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>packaging-payment</artifactId>
                    <packaging>jar</packaging>
                    <dependencies>
                        <dependency>
                            <groupId>com.sample.test</groupId>
                            <artifactId>payment-service</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        // Enregistrer et analyser le projet
        Project project = orchestrator.registerProject("Test App", tempDir.toString(), "main");
        MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(project.id(), null);
        String analysisId = context.analysisId();

        // 3. Tester GET /api/analyses/{id}/module-reorganization
        String getRes = mockMvc.perform(get("/api/analyses/" + analysisId + "/module-reorganization"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups").isArray())
                .andExpect(jsonPath("$.groups[0].packagingArtifactId").value("packaging-payment"))
                .andExpect(jsonPath("$.groups[0].targetSubProjectDir").value("payment"))
                .andExpect(jsonPath("$.groups[0].childModules[0].moduleArtifactId").value("payment-service"))
                .andExpect(jsonPath("$.unassignedModules[0]").value("common-utils"))
                .andExpect(jsonPath("$.applied").value(false))
                .andReturn().getResponse().getContentAsString();

        ModuleReorganizationPlan plan = objectMapper.readValue(getRes, ModuleReorganizationPlan.class);
        assertThat(plan.groups()).hasSize(1);
        assertThat(plan.gitMoveCommands()).isNotEmpty();

        // 4. Tester POST /api/analyses/{id}/module-reorganization/preview avec personnalisation par l'utilisateur
        PackagingSubProjectGroup originalGroup = plan.groups().get(0);
        PackagingSubProjectGroup customizedGroup = originalGroup.withTargetSubProjectDir("custom-payment-suite");

        ModuleReorganizationPlan customizedPlan = new ModuleReorganizationPlan(
                plan.parentPomPath(),
                List.of(customizedGroup),
                plan.unassignedModules(),
                plan.rootPomDiff(),
                plan.subProjectPomDiffs(),
                plan.gitMoveCommands(),
                false,
                "Personnalisé par l'utilisateur"
        );

        String previewRes = mockMvc.perform(post("/api/analyses/" + analysisId + "/module-reorganization/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(customizedPlan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].targetSubProjectDir").value("custom-payment-suite"))
                .andExpect(jsonPath("$.rootPomDiff").value(org.hamcrest.Matchers.containsString("custom-payment-suite")))
                .andReturn().getResponse().getContentAsString();

        ModuleReorganizationPlan updatedPreview = objectMapper.readValue(previewRes, ModuleReorganizationPlan.class);
        assertThat(updatedPreview.subProjectPomDiffs()).containsKey("custom-payment-suite/pom.xml");
        assertThat(updatedPreview.gitMoveCommands())
                .anyMatch(cmd -> cmd.contains("custom-payment-suite"));

        // 5. Tester POST /api/analyses/{id}/module-reorganization/apply (Application physique contrôlée)
        mockMvc.perform(post("/api/analyses/" + analysisId + "/module-reorganization/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatedPreview)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        // 6. Vérifications physiques sur le disque
        // Dossier du sous-projet personnalisé créé
        Path targetSuiteDir = tempDir.resolve("custom-payment-suite");
        assertThat(targetSuiteDir).exists().isDirectory();
        assertThat(targetSuiteDir.resolve("pom.xml")).exists();

        // pom.xml du sous-projet : artifactId préservé et modules enfants déclarés
        String subPomContent = Files.readString(targetSuiteDir.resolve("pom.xml"));
        assertThat(subPomContent).contains("<artifactId>packaging-payment</artifactId>");
        assertThat(subPomContent).contains("<module>payment-service</module>");

        // Module enfant payment-service déplacé à l'intérieur du sous-projet
        Path movedChildDir = targetSuiteDir.resolve("payment-service");
        assertThat(movedChildDir).exists().isDirectory();
        assertThat(movedChildDir.resolve("pom.xml")).exists();

        // Ancien packaging et ancien payment-service supprimés de la racine
        assertThat(tempDir.resolve("packaging-payment")).doesNotExist();
        assertThat(tempDir.resolve("payment-service")).doesNotExist();

        // common-utils toujours à la racine car transverse
        assertThat(tempDir.resolve("common-utils")).exists();

        // pom.xml racine mis à jour avec le sous-projet
        String rootPomContent = Files.readString(tempDir.resolve("pom.xml"));
        assertThat(rootPomContent).contains("<module>custom-payment-suite</module>");
        assertThat(rootPomContent).contains("<module>common-utils</module>");
        assertThat(rootPomContent).doesNotContain("<module>packaging-payment</module>");
        assertThat(rootPomContent).doesNotContain("<module>payment-service</module>");
    }

    @Test
    void shouldSupportDynamicSubProjectCreationAndDragDropReorganization(@TempDir Path tempDir) throws Exception {
        // Setup project with 3 modules
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample.dragdrop</groupId>
                    <artifactId>dragdrop-root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>core-utils</module>
                        <module>user-api</module>
                        <module>billing-service</module>
                    </modules>
                </project>
                """);

        for (String m : List.of("core-utils", "user-api", "billing-service")) {
            Path mDir = tempDir.resolve(m);
            Files.createDirectories(mDir);
            Files.writeString(mDir.resolve("pom.xml"), "<project><artifactId>" + m + "</artifactId></project>");
        }

        Project project = orchestrator.registerProject("DragDrop App", tempDir.toString(), "main");
        MigrationOrchestratorService.AnalysisContext context = orchestrator.runFullAnalysis(project.id(), null);
        String analysisId = context.analysisId();

        // 1. Initially, no packaging exists so all are unassigned
        String initGet = mockMvc.perform(get("/api/analyses/" + analysisId + "/module-reorganization"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ModuleReorganizationPlan initialPlan = objectMapper.readValue(initGet, ModuleReorganizationPlan.class);
        assertThat(initialPlan.groups()).isEmpty();

        // 2. User adds a group "billing-subproject" and drags "billing-service" into it
        PackagingSubProjectGroup billingGroup = new PackagingSubProjectGroup(
                "billing-subproject",
                "billing-subproject",
                "billing-subproject",
                List.of(new ModuleReorganizationItem("billing-service", "billing-service", "billing-subproject/billing-service", true)),
                true,
                "MANUAL"
        );

        // User also adds a group "core-platform" and drags "core-utils" into it, leaving "user-api" unassigned
        PackagingSubProjectGroup coreGroup = new PackagingSubProjectGroup(
                "core-platform",
                "core-platform",
                "core-platform",
                List.of(new ModuleReorganizationItem("core-utils", "core-utils", "core-platform/core-utils", true)),
                true,
                "MANUAL"
        );

        ModuleReorganizationPlan userCustomPlan = new ModuleReorganizationPlan(
                "pom.xml",
                List.of(billingGroup, coreGroup),
                List.of("user-api"),
                "",
                java.util.Map.of(),
                List.of(),
                false,
                "Plan personnalisé avec drag-and-drop et création de groupes"
        );

        // 3. Preview
        String previewRes = mockMvc.perform(post("/api/analyses/" + analysisId + "/module-reorganization/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(userCustomPlan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(2))
                .andExpect(jsonPath("$.subProjectPomDiffs['billing-subproject/pom.xml']").exists())
                .andExpect(jsonPath("$.subProjectPomDiffs['core-platform/pom.xml']").exists())
                .andReturn().getResponse().getContentAsString();

        ModuleReorganizationPlan previewPlan = objectMapper.readValue(previewRes, ModuleReorganizationPlan.class);

        // 4. Apply
        mockMvc.perform(post("/api/analyses/" + analysisId + "/module-reorganization/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(previewPlan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        // 5. Verify filesystem
        assertThat(tempDir.resolve("billing-subproject/pom.xml")).exists();
        assertThat(tempDir.resolve("billing-subproject/billing-service/pom.xml")).exists();
        assertThat(tempDir.resolve("billing-service")).doesNotExist();

        assertThat(tempDir.resolve("core-platform/pom.xml")).exists();
        assertThat(tempDir.resolve("core-platform/core-utils/pom.xml")).exists();
        assertThat(tempDir.resolve("core-utils")).doesNotExist();

        // user-api remains at root
        assertThat(tempDir.resolve("user-api/pom.xml")).exists();

        // Root pom.xml aggregates billing-subproject, core-platform and user-api
        String rootPom = Files.readString(tempDir.resolve("pom.xml"));
        assertThat(rootPom).contains("<module>billing-subproject</module>");
        assertThat(rootPom).contains("<module>core-platform</module>");
        assertThat(rootPom).contains("<module>user-api</module>");
        assertThat(rootPom).doesNotContain("<module>billing-service</module>");
        assertThat(rootPom).doesNotContain("<module>core-utils</module>");
    }
}
