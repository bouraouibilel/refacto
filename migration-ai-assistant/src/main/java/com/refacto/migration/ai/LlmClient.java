package com.refacto.migration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client HTTP pour modèles de langage compatibles standard OpenAI (/v1/chat/completions).
 * Fonctionne avec Ollama, vLLM, LM Studio, OpenAI, Azure OpenAI, Groq, etc.
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public LlmClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Optional<String> sendChatCompletion(LlmConfig config, List<ChatMessage> messages) {
        if (!config.enabled()) {
            return Optional.empty();
        }

        try {
            String baseUrl = config.baseUrl().replaceAll("/+$", "");
            String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions";

            Map<String, Object> body = new HashMap<>();
            body.put("model", config.model());
            body.put("temperature", config.temperature());

            List<Map<String, String>> msgList = new ArrayList<>();
            for (ChatMessage m : messages) {
                msgList.add(Map.of("role", m.role(), "content", m.content()));
            }
            body.put("messages", msgList);

            String requestJson = mapper.writeValueAsString(body);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json");

            if (config.apiKey() != null && !config.apiKey().isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + config.apiKey().trim());
            }

            HttpRequest request = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(requestJson)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    String answer = choices.get(0).path("message").path("content").asText();
                    if (answer != null && !answer.isBlank()) {
                        return Optional.of(answer.trim());
                    }
                }
            } else {
                log.warn("Erreur réponse LLM HTTP {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Impossible de joindre le service LLM ({}) : {}", config.baseUrl(), e.getMessage());
        }

        return Optional.empty();
    }

    public boolean testConnection(LlmConfig config) {
        try {
            List<ChatMessage> testMsgs = List.of(
                    ChatMessage.user("Réponds simplement 'OK'.")
            );
            return sendChatCompletion(config, testMsgs).isPresent();
        } catch (Exception e) {
            return false;
        }
    }
}
