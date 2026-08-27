package com.zhitu.ai;

import java.util.Optional;
import java.util.Map;

/**
 * Minimal abstraction for an OpenAI-compatible chat model.
 *
 * Keeping the provider behind this interface makes the question-answering
 * pipeline testable and allows DashScope, OpenAI, local vLLM and compatible
 * gateways to share the same implementation.
 */
public interface AiClient {

    Optional<String> complete(String system, String user);

    default Optional<String> complete(String system, String user, String model, int maxTokens, double temperature) {
        return complete(system, user);
    }

    default Optional<String> completeVision(
            String system,
            String user,
            String imageMimeType,
            byte[] imageBytes,
            String model,
            int maxTokens,
            double temperature
    ) {
        return Optional.empty();
    }

    boolean enabled();

    default String modelName() {
        return "disabled";
    }

    default String visionModelName() {
        return modelName();
    }

    default boolean visionEnabled() {
        return false;
    }

    default boolean visionImageCapable() {
        return false;
    }

    default Optional<String> fallbackModelName() {
        return Optional.empty();
    }

    default Optional<String> lastSuccessfulModel() {
        return Optional.empty();
    }

    default Optional<String> lastError() {
        return Optional.empty();
    }

    default Map<String, Object> diagnostics() {
        return Map.of(
                "enabled", enabled(),
                "model", modelName(),
                "visionEnabled", visionEnabled(),
                "visionModel", visionModelName(),
                "lastError", lastError().orElse("")
        );
    }
}
