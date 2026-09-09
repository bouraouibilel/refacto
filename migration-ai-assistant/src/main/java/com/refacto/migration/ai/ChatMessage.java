package com.refacto.migration.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Message d'échange avec le LLM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
        String role,
        String content
) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
