package com.zhitu.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;

@Component
public class OpenAiCompatibleClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private final RestClient client;
    private final RestClient fallbackClient;
    private final RestClient deepSeekClient;
    private final RestClient visionClient;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final boolean visionEnabled;
    private final String apiKey;
    private final String fallbackApiKey;
    private final String deepSeekApiKey;
    private final String visionApiKey;
    private final String baseUrl;
    private final String fallbackBaseUrl;
    private final String deepSeekBaseUrl;
    private final String visionBaseUrl;
    private final String model;
    private final String visionModel;
    private final String fallbackModel;
    private final int maxTokens;
    private volatile String lastError;
    private volatile String lastSuccessfulModel;

    public OpenAiCompatibleClient(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model:qwen-plus}") String model,
            @Value("${app.ai.fallback-model:}") String fallbackModel,
            @Value("${app.ai.fallback-base-url:${app.ai.base-url}}") String fallbackBaseUrl,
            @Value("${app.ai.fallback-api-key:${app.ai.api-key:}}") String fallbackApiKey,
            @Value("${app.ai.deepseek-base-url:https://api.deepseek.com/v1}") String deepSeekBaseUrl,
            @Value("${app.ai.deepseek-api-key:${DEEPSEEK_API_KEY:}}") String deepSeekApiKey,
            @Value("${app.ai.resume.vision-base-url:${app.ai.base-url}}") String visionBaseUrl,
            @Value("${app.ai.resume.vision-api-key:${app.ai.api-key:}}") String visionApiKey,
            @Value("${app.ai.resume.vision-model:${app.ai.resume.model:${app.ai.model:qwen-plus}}}") String visionModel,
            @Value("${app.ai.enabled:true}") boolean configuredEnabled,
            @Value("${app.ai.resume.enabled:${app.ai.enabled:true}}") boolean visionConfiguredEnabled,
            @Value("${app.ai.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.ai.read-timeout-ms:60000}") int readTimeoutMs,
            @Value("${app.ai.max-tokens:5000}") int maxTokens
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(5000, readTimeoutMs));
        String normalizedBaseUrl = stripTrailingSlash(baseUrl);
        String normalizedFallbackBaseUrl = stripTrailingSlash(fallbackBaseUrl == null || fallbackBaseUrl.isBlank()
                ? normalizedBaseUrl
                : fallbackBaseUrl);
        String normalizedDeepSeekBaseUrl = stripTrailingSlash(deepSeekBaseUrl == null || deepSeekBaseUrl.isBlank()
                ? "https://api.deepseek.com/v1"
                : deepSeekBaseUrl);
        String normalizedVisionBaseUrl = stripTrailingSlash(visionBaseUrl == null || visionBaseUrl.isBlank() ? baseUrl : visionBaseUrl);
        this.baseUrl = normalizedBaseUrl;
        this.fallbackBaseUrl = normalizedFallbackBaseUrl;
        this.deepSeekBaseUrl = normalizedDeepSeekBaseUrl;
        this.visionBaseUrl = normalizedVisionBaseUrl;
        this.client = builder
                .baseUrl(normalizedBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.fallbackClient = builder
                .baseUrl(normalizedFallbackBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.deepSeekClient = builder
                .baseUrl(normalizedDeepSeekBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.visionClient = builder
                .baseUrl(normalizedVisionBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.fallbackApiKey = fallbackApiKey == null || fallbackApiKey.isBlank()
                ? this.apiKey
                : fallbackApiKey.trim();
        this.deepSeekApiKey = deepSeekApiKey == null ? "" : deepSeekApiKey.trim();
        boolean sameEndpoint = normalizedBaseUrl.equalsIgnoreCase(normalizedVisionBaseUrl);
        this.visionApiKey = visionApiKey == null || visionApiKey.isBlank()
                ? (sameEndpoint ? this.apiKey : "")
                : visionApiKey.trim();
        this.model = model == null || model.isBlank() ? "qwen-plus" : model.trim();
        this.visionModel = visionModel == null || visionModel.isBlank() ? this.model : visionModel.trim();
        this.fallbackModel = fallbackModel == null ? "" : fallbackModel.trim();
        this.maxTokens = Math.max(256, Math.min(maxTokens, 8192));
        this.enabled = configuredEnabled
                && (!this.apiKey.isBlank() || !this.deepSeekApiKey.isBlank() || !this.fallbackApiKey.isBlank());
        this.visionEnabled = visionConfiguredEnabled && !this.visionApiKey.isBlank();

        if (configuredEnabled && !this.enabled) {
            log.info("大模型问答未启用：未配置 app.ai.api-key / AI_API_KEY / DEEPSEEK_API_KEY");
        }
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String visionModelName() {
        return visionModel;
    }

    @Override
    public boolean visionEnabled() {
        return visionEnabled;
    }

    @Override
    public boolean visionImageCapable() {
        return visionEnabled && !isKnownTextOnlyImageModel(visionModel);
    }

    @Override
    public Optional<String> fallbackModelName() {
        if (fallbackModel.isBlank() || fallbackModel.equalsIgnoreCase(model)) {
            return Optional.empty();
        }
        return Optional.of(fallbackModel);
    }

    @Override
    public Optional<String> lastSuccessfulModel() {
        return Optional.ofNullable(lastSuccessfulModel);
    }

    @Override
    public Optional<String> lastError() {
        return Optional.ofNullable(lastError);
    }

    @Override
    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("model", model);
        result.put("baseUrl", maskBaseUrl(baseUrl));
        result.put("apiKeyConfigured", !apiKey.isBlank());
        result.put("fallbackModel", fallbackModelName().orElse(""));
        result.put("fallbackBaseUrl", fallbackModelName().isPresent() ? maskBaseUrl(fallbackBaseUrl) : "");
        result.put("fallbackKeyConfigured", fallbackModelName().isPresent() && !fallbackApiKey.isBlank());
        result.put("deepSeekBaseUrl", maskBaseUrl(deepSeekBaseUrl));
        result.put("deepSeekKeyConfigured", !deepSeekApiKey.isBlank());
        result.put("visionEnabled", visionEnabled);
        result.put("visionModel", visionModel);
        result.put("visionBaseUrl", maskBaseUrl(visionBaseUrl));
        result.put("lastSuccessfulModel", lastSuccessfulModel().orElse(""));
        result.put("lastError", lastError().orElse(""));
        result.put("lastErrorHint", readableError(lastError));
        result.put("retryPolicy", "主模型瞬时错误自动重试 1 次，失败后切换备用模型");
        return result;
    }

    @Override
    public Optional<String> complete(String system, String user) {
        return complete(system, user, model, maxTokens, 0.1D);
    }

    @Override
    public Optional<String> complete(String system, String user, String requestedModel, int requestedMaxTokens, double temperature) {
        if (!enabled) {
            return Optional.empty();
        }
        String primaryModel = requestedModel == null || requestedModel.isBlank() ? model : requestedModel.trim();
        int primaryMaxTokens = adjustTextMaxTokens(primaryModel,
                Math.max(256, Math.min(requestedMaxTokens <= 0 ? maxTokens : requestedMaxTokens, 8192)));
        double safeTemperature = Math.max(0D, Math.min(temperature, 1D));

        try {
            ModelEndpoint primaryEndpoint = endpointFor(primaryModel);
            String answer = requestCompletionWithRetry(primaryEndpoint.client(), primaryEndpoint.apiKey(),
                    primaryModel, system, user, primaryMaxTokens, safeTemperature);
            lastError = null;
            lastSuccessfulModel = primaryModel;
            return Optional.of(answer);
        } catch (Exception ex) {
            String primaryError = rootMessage(ex);
            log.warn("大模型调用失败（model={}）：{}", primaryModel, primaryError);

            if (shouldRetryForFinalAnswer(primaryError) && primaryMaxTokens < 8192) {
                int retryMaxTokens = Math.min(8192, Math.max(4096, primaryMaxTokens * 2));
                try {
                    String retrySystem = system + "\n\nOutput policy: answer directly with final Chinese response only. Do not spend the response budget on hidden reasoning.";
                    ModelEndpoint retryEndpoint = endpointFor(primaryModel);
                    String answer = requestCompletionWithRetry(retryEndpoint.client(), retryEndpoint.apiKey(),
                            primaryModel, retrySystem, user, retryMaxTokens, Math.min(safeTemperature, 0.2D));
                    lastError = null;
                    lastSuccessfulModel = primaryModel;
                    log.warn("LLM first response had no final content; retry succeeded with max_tokens={} model={}",
                            retryMaxTokens, primaryModel);
                    return Optional.of(answer);
                } catch (Exception retryEx) {
                    primaryError = primaryError + "; retry failed: " + rootMessage(retryEx);
                    log.warn("LLM retry after increasing max_tokens failed, model={}: {}", primaryModel, rootMessage(retryEx));
                }
            }

            if (!fallbackModel.isBlank() && !fallbackModel.equalsIgnoreCase(primaryModel)) {
                try {
                    int fallbackMaxTokens = Math.max(2048, Math.min(primaryMaxTokens, 4096));
                    String answer = requestCompletionWithRetry(fallbackClient, fallbackApiKey, fallbackModel, system, user, fallbackMaxTokens, safeTemperature);
                    lastError = "主模型 " + primaryModel + " 调用失败，已自动使用备用模型 " + fallbackModel;
                    lastSuccessfulModel = fallbackModel;
                    log.warn("主模型调用失败，已使用备用模型回答（primary={}, fallback={}, endpoint={}）：{}",
                            primaryModel, fallbackModel, maskBaseUrl(fallbackBaseUrl), primaryError);
                    return Optional.of(answer);
                } catch (Exception fallbackEx) {
                    String fallbackError = rootMessage(fallbackEx);
                    lastError = primaryError + "；备用模型 " + fallbackModel + " 也失败：" + fallbackError;
                    log.warn("备用模型调用失败（model={}）：{}", fallbackModel, fallbackError);
                    return Optional.empty();
                }
            }

            lastError = primaryError;
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> completeVision(
            String system,
            String user,
            String imageMimeType,
            byte[] imageBytes,
            String requestedModel,
            int requestedMaxTokens,
            double temperature
    ) {
        if (!visionEnabled || imageBytes == null || imageBytes.length == 0) {
            return Optional.empty();
        }
        String primaryModel = requestedModel == null || requestedModel.isBlank() ? visionModel : requestedModel.trim();
        int primaryMaxTokens = Math.max(256, Math.min(requestedMaxTokens <= 0 ? maxTokens : requestedMaxTokens, 8192));
        double safeTemperature = Math.max(0D, Math.min(temperature, 1D));
        try {
            String answer = requestVisionCompletion(primaryModel, system, user, imageMimeType, imageBytes, primaryMaxTokens, safeTemperature);
            lastError = null;
            lastSuccessfulModel = primaryModel;
            return Optional.of(answer);
        } catch (Exception ex) {
            String primaryError = rootMessage(ex);
            log.warn("视觉模型调用失败（model={}）：{}", primaryModel, primaryError);
            lastError = primaryError;
            return Optional.empty();
        }
    }

    private String requestCompletionWithRetry(
            RestClient targetClient,
            String targetApiKey,
            String requestedModel,
            String system,
            String user,
            int requestedMaxTokens,
            double temperature
    ) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return requestCompletion(targetClient, targetApiKey, requestedModel, system, user, requestedMaxTokens, temperature);
            } catch (Exception ex) {
                lastException = ex;
                if (attempt >= 2 || !isTransientModelError(ex)) {
                    throw ex;
                }
                long backoffMs = 600L * attempt;
                log.warn("模型瞬时调用异常，准备第 {} 次重试（model={}, backoff={}ms）：{}",
                        attempt + 1, requestedModel, backoffMs, rootMessage(ex));
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw lastException == null ? new IllegalStateException("模型调用失败") : lastException;
    }

    private String requestCompletion(
            RestClient targetClient,
            String targetApiKey,
            String requestedModel,
            String system,
            String user,
            int requestedMaxTokens,
            double temperature
    )
            throws Exception {
        if (targetApiKey == null || targetApiKey.isBlank()) {
            throw new IllegalStateException("模型 API Key 未配置");
        }
        Map<String, Object> body = Map.of(
                "model", requestedModel,
                "temperature", temperature,
                "max_tokens", requestedMaxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                )
        );
        String responseBody = targetClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + targetApiKey)
                .body(body)
                .retrieve()
                .body(String.class);
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("模型响应为空");
        }
        return extractAssistantText(responseBody, "model");
    }

    private String requestVisionCompletion(
            String requestedModel,
            String system,
            String user,
            String imageMimeType,
            byte[] imageBytes,
            int requestedMaxTokens,
            double temperature
    ) throws Exception {
        String mimeType = imageMimeType == null || imageMimeType.isBlank() ? "image/png" : imageMimeType;
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> body = Map.of(
                "model", requestedModel,
                "temperature", temperature,
                "max_tokens", requestedMaxTokens,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("type", "text", "text", user),
                                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                                )
                        )
                )
        );
        String responseBody = visionClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + visionApiKey)
                .body(body)
                .retrieve()
                .body(String.class);
        if (responseBody != null) {
            return extractAssistantText(responseBody, "vision model");
        }

        JsonNode root = mapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String answer = content.isTextual() ? content.asText().trim() : "";
        if (answer.isBlank()) {
            throw new IllegalStateException("视觉模型响应中没有 choices[0].message.content");
        }
        return answer;
    }

    private String extractAssistantText(String responseBody, String label) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String answer = firstNonBlank(
                textFromNode(message.path("content")),
                textFromNode(choice.path("text")),
                textFromNode(root.path("output_text")),
                textFromOutputArray(root.path("output"))
        );
        if (!answer.isBlank()) {
            return answer.trim();
        }

        String finishReason = choice.path("finish_reason").asText("");
        String reasoning = textFromNode(message.path("reasoning_content"));
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new IllegalStateException(label + " output was truncated before final content; increase max_tokens or reduce evidence context");
        }
        if (!reasoning.isBlank()) {
            throw new IllegalStateException(label + " returned reasoning_content but no final message content");
        }
        throw new IllegalStateException(label + " response has no usable assistant text");
    }

    private static String textFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText().trim();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                String value = firstNonBlank(
                        item.isTextual() ? item.asText() : "",
                        item.path("text").asText(""),
                        item.path("content").asText(""),
                        item.path("output_text").asText("")
                );
                if (!value.isBlank()) {
                    if (!builder.isEmpty()) {
                        builder.append('\n');
                    }
                    builder.append(value.trim());
                }
            }
            return builder.toString().trim();
        }
        return firstNonBlank(
                node.path("text").asText(""),
                node.path("content").asText(""),
                node.path("output_text").asText("")
        ).trim();
    }

    private static String textFromOutputArray(JsonNode output) {
        if (output == null || !output.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode item : output) {
            String value = textFromNode(item.path("content"));
            if (value.isBlank()) {
                value = firstNonBlank(item.path("text").asText(""), item.path("output_text").asText(""));
            }
            if (!value.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(value.trim());
            }
        }
        return builder.toString().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private ModelEndpoint endpointFor(String requestedModel) {
        if (looksLikeDeepSeekModel(requestedModel) && !deepSeekApiKey.isBlank()) {
            return new ModelEndpoint(deepSeekClient, deepSeekApiKey, deepSeekBaseUrl);
        }
        return new ModelEndpoint(client, apiKey, baseUrl);
    }

    private static boolean looksLikeDeepSeekModel(String requestedModel) {
        return requestedModel != null
                && requestedModel.trim().toLowerCase(java.util.Locale.ROOT).startsWith("deepseek");
    }

    private static int adjustTextMaxTokens(String model, int requestedMaxTokens) {
        String normalized = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("deepseek-v4") || normalized.contains("reasoner")) {
            return Math.max(requestedMaxTokens, 4096);
        }
        return requestedMaxTokens;
    }

    private static boolean shouldRetryForFinalAnswer(String error) {
        String value = error == null ? "" : error.toLowerCase(java.util.Locale.ROOT);
        return value.contains("truncated before final content")
                || value.contains("reasoning_content but no final")
                || value.contains("no usable assistant text")
                || value.contains("choices[0].message.content");
    }

    private static boolean isTransientModelError(Throwable throwable) {
        if (throwable instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 408
                    || status == 409
                    || status == 425
                    || status == 429
                    || status >= 500;
        }
        String message = rootMessage(throwable).toLowerCase(java.util.Locale.ROOT);
        return message.contains("timeout")
                || message.contains("timed out")
                || message.contains("connection reset")
                || message.contains("connection refused")
                || message.contains("temporarily unavailable")
                || message.contains("too many requests")
                || message.contains("rate limit")
                || message.contains("429")
                || message.contains("502")
                || message.contains("503")
                || message.contains("504");
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isBlank()) {
            throw new IllegalArgumentException("app.ai.base-url 不能为空");
        }
        return result;
    }

    private static boolean isKnownTextOnlyImageModel(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("deepseek-chat")
                || normalized.equals("deepseek-reasoner")
                || normalized.startsWith("deepseek-v4")
                || normalized.startsWith("deepseek-coder");
    }

    private static String readableError(String error) {
        if (error == null || error.isBlank()) {
            return "";
        }
        String value = error.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("permission denied") || value.contains("getsockopt")) {
            return "后端进程没有外网访问权限，需允许 Java 进程访问模型网关。";
        }
        if (value.contains("401") || value.contains("unauthorized") || value.contains("invalid api key")) {
            return "API Key 无效或未授权，请重新配置 AI_API_KEY / DEEPSEEK_API_KEY。";
        }
        if (value.contains("403") || value.contains("forbidden")) {
            return "模型网关拒绝访问，常见原因是 Key 权限、余额、地域或模型白名单不匹配。";
        }
        if (value.contains("404") || value.contains("model")) {
            return "模型名或 Base URL 不匹配，请确认 AI_MODEL 与 AI_BASE_URL 属于同一供应商。";
        }
        if (value.contains("timeout") || value.contains("timed out")) {
            return "模型响应超时，建议使用 deepseek-chat 或配置备用模型。";
        }
        return limit(error, 220);
    }

    private static String maskBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("(?i)(api-key=|key=)[^&]+", "$1***");
    }

    private static String rootMessage(Throwable throwable) {
        if (throwable instanceof RestClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                return limit(responseException.getStatusCode().value() + " " + body, 800);
            }
        }
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank()
                ? cursor.getClass().getSimpleName()
                : message;
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record ModelEndpoint(RestClient client, String apiKey, String baseUrl) {}
}
