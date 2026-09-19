package com.refacto.migration.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.AnalysisSummary;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisHistoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MigrationOrchestratorService orchestrator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldTrackAnalysisHistoryAndSupportAsyncExecutionLifecycle(@TempDir Path tempDir) throws Exception {
        // 1. Setup small project with pom.xml
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.sample.history</groupId>
                    <artifactId>history-test-app</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                </project>
                """);
        Path src = tempDir.resolve("src/main/java/com/sample");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package com.sample; public class App {}");

        Project project = orchestrator.registerProject("History Test App", tempDir.toString(), "main");

        // 2. Start Async Analysis: POST /api/projects/{id}/analyses?async=true
        String asyncRes = mockMvc.perform(post("/api/projects/" + project.id() + "/analyses?async=true")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.analysisId").isString())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();

        AnalysisSummary initialSummary = objectMapper.readValue(asyncRes, AnalysisSummary.class);
        String analysisId = initialSummary.analysisId();
        assertThat(analysisId).isNotNull();

        // 3. Verify that analysis is immediately listed in GET /api/analyses
        String listRes = mockMvc.perform(get("/api/analyses"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<AnalysisSummary> summaries = objectMapper.readValue(listRes, new TypeReference<List<AnalysisSummary>>() {});
        assertThat(summaries).anyMatch(s -> s.analysisId().equals(analysisId));

        // 4. Poll GET /api/analyses/{id}/status until completion
        long timeoutMs = 15000;
        long start = System.currentTimeMillis();
        AnalysisSummary finalStatus = null;
        while (System.currentTimeMillis() - start < timeoutMs) {
            String statusRes = mockMvc.perform(get("/api/analyses/" + analysisId + "/status"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            AnalysisSummary current = objectMapper.readValue(statusRes, AnalysisSummary.class);
            if ("COMPLETED".equals(current.status())) {
                finalStatus = current;
                break;
            }
            Thread.sleep(200);
        }

        assertThat(finalStatus).isNotNull();
        assertThat(finalStatus.status()).isEqualTo("COMPLETED");
        assertThat(finalStatus.progressPercent()).isEqualTo(100);
        assertThat(finalStatus.modulesCount()).isGreaterThanOrEqualTo(1);

        // 5. Verify GET /api/analyses/{id} returns full AnalysisContext
        mockMvc.perform(get("/api/analyses/" + analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.modules").isArray());

        // 6. Test DELETE /api/analyses/{id}
        mockMvc.perform(delete("/api/analyses/" + analysisId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/analyses/" + analysisId))
                .andExpect(status().isNotFound());
    }
}
