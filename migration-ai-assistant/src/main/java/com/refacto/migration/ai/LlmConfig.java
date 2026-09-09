package com.refacto.migration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configuration du client LLM (Ollama, vLLM, OpenAI, DeepSeek, etc.).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmConfig(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        int timeoutSeconds,
        boolean enabled
) {
    public static LlmConfig defaultOffline() {
        return new LlmConfig(
                "mock",
                "http://localhost:11434/v1",
                "",
                "llama3.2",
                0.2,
                15,
                false
        );
    }

    public static LlmConfig defaultOllama() {
        return new LlmConfig(
                "ollama",
                "http://localhost:11434/v1",
                "",
                "llama3.2",
                0.2,
                30,
                true
        );
    }
}
