package com.refacto.migration.app.controller;

import com.refacto.migration.ai.ChatMessage;
import com.refacto.migration.ai.LlmConfig;
import com.refacto.migration.app.service.MigrationOrchestratorService;
import com.refacto.migration.core.model.Finding;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Contrôleur REST pour l'assistant IA de migration (support LLM interactif, explications et refactoring).
 */
@RestController
@RequestMapping("/api/assistant")
@CrossOrigin(origins = "*")
public class AssistantController {

    private final MigrationOrchestratorService orchestrator;

    public AssistantController(MigrationOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    public record ChatRequest(
            String analysisId,
            String findingId,
            String message,
            List<ChatMessage> history
    ) {}

    public record ChatResponse(
            String answer,
            String provider,
            boolean isLlm
    ) {}

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String systemContext = buildContext(request.analysisId(), request.findingId());
        String answer = orchestrator.getAiAssistant().askAssistant(
                request.message(),
                systemContext,
                request.history()
        );

        LlmConfig cfg = orchestrator.getAiAssistant().getConfig();
        return ResponseEntity.ok(new ChatResponse(answer, cfg.provider(), cfg.enabled()));
    }

    public record ExplainRequest(String analysisId, String codeContext) {}

    @PostMapping("/explain-finding/{findingId}")
    public ResponseEntity<ChatResponse> explainFinding(
            @PathVariable String findingId,
            @RequestBody(required = false) ExplainRequest request
    ) {
        String analysisId = request != null ? request.analysisId() : null;
        Finding finding = findFinding(analysisId, findingId);
        if (finding == null) {
            return ResponseEntity.notFound().build();
        }

        String codeContext = request != null ? request.codeContext() : null;
        String answer = orchestrator.getAiAssistant().explainFinding(finding, codeContext);
        LlmConfig cfg = orchestrator.getAiAssistant().getConfig();
        return ResponseEntity.ok(new ChatResponse(answer, cfg.provider(), cfg.enabled()));
    }

    public record SuggestCodeRequest(String analysisId, String codeSnippet) {}

    @PostMapping("/suggest-code/{findingId}")
    public ResponseEntity<ChatResponse> suggestCode(
            @PathVariable String findingId,
            @RequestBody(required = false) SuggestCodeRequest request
    ) {
        String analysisId = request != null ? request.analysisId() : null;
        Finding finding = findFinding(analysisId, findingId);
        if (finding == null) {
            return ResponseEntity.notFound().build();
        }

        String snippet = request != null ? request.codeSnippet() : null;
        String answer = orchestrator.getAiAssistant().suggestRefactoring(finding, snippet);
        LlmConfig cfg = orchestrator.getAiAssistant().getConfig();
        return ResponseEntity.ok(new ChatResponse(answer, cfg.provider(), cfg.enabled()));
    }

    @GetMapping("/config")
    public ResponseEntity<LlmConfig> getConfig() {
        return ResponseEntity.ok(orchestrator.getAiAssistant().getConfig());
    }

    @PostMapping("/config")
    public ResponseEntity<LlmConfig> updateConfig(@RequestBody LlmConfig newConfig) {
        orchestrator.getAiAssistant().updateConfig(newConfig);
        return ResponseEntity.ok(orchestrator.getAiAssistant().getConfig());
    }

    @PostMapping("/config/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody(required = false) LlmConfig configToTest) {
        if (configToTest != null) {
            orchestrator.getAiAssistant().updateConfig(configToTest);
        }
        boolean ok = orchestrator.getAiAssistant().testLlmConnection();
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "provider", orchestrator.getAiAssistant().getConfig().provider(),
                "message", ok ? "Connexion LLM réussie avec le modèle " + orchestrator.getAiAssistant().getConfig().model()
                              : "Impossible de joindre le service LLM à " + orchestrator.getAiAssistant().getConfig().baseUrl() + ". Mode base de connaissances hors-ligne actif."
        ));
    }

    private Finding findFinding(String analysisId, String findingId) {
        Optional<MigrationOrchestratorService.AnalysisContext> ctxOpt = (analysisId != null && !analysisId.isBlank())
                ? orchestrator.getAnalysis(analysisId)
                : orchestrator.getLatestAnalysis();

        return ctxOpt.flatMap(ctx -> ctx.findings().stream()
                .filter(f -> f.id().equals(findingId) || f.recipeId().equals(findingId))
                .findFirst()).orElse(null);
    }

    private String buildContext(String analysisId, String findingId) {
        Optional<MigrationOrchestratorService.AnalysisContext> ctxOpt = (analysisId != null && !analysisId.isBlank())
                ? orchestrator.getAnalysis(analysisId)
                : orchestrator.getLatestAnalysis();

        if (ctxOpt.isEmpty()) {
            return "";
        }

        MigrationOrchestratorService.AnalysisContext ctx = ctxOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Projet : %s (%d modules, %d batchs, score risque : %d)\n",
                ctx.project() != null ? ctx.project().name() : "N/A",
                ctx.modules().size(), ctx.batches().size(), ctx.riskAssessment().score()));

        if (findingId != null && !findingId.isBlank()) {
            ctx.findings().stream()
                    .filter(f -> f.id().equals(findingId) || f.recipeId().equals(findingId))
                    .findFirst()
                    .ifPresent(f -> sb.append(String.format("Finding concerné : [%s] %s dans %s (L%d)\nCode : %s\n",
                            f.recipeId(), f.description(), f.filePath(), f.startLine(), f.codeSnippet())));
        }

        return sb.toString();
    }
}
