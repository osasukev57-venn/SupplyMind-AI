package com.supplymind.agent.infrastructure.springai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/**
 * Creates the concrete cloud {@link ChatModel} used by the Spring AI infrastructure adapter.
 *
 * <p>The bean is deliberately absent unless {@code SUPPLYMIND_LLM_ENABLED=true}. This preserves
 * credential-free startup and the deterministic Java-template fallback. When enabled, incomplete
 * or unsafe configuration fails closed during startup; the API key is never logged or embedded in
 * an exception message.</p>
 */
@Configuration(proxyBeanMethods = false)
public class CloudLlmConfiguration {

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);

    @Bean
    @ConditionalOnProperty(prefix = "supplymind.agent.llm", name = "enabled", havingValue = "true")
    ChatModel supplyMindCloudChatModel(
            @Value("${supplymind.agent.llm.provider:}") String provider,
            @Value("${supplymind.agent.llm.model:}") String model,
            @Value("${supplymind.agent.llm.base-url:}") String baseUrl,
            @Value("${supplymind.agent.llm.api-key:}") String apiKey,
            @Value("${supplymind.agent.llm.completions-path:/v1/chat/completions}") String completionsPath,
            @Value("${supplymind.agent.llm.timeout:30s}") String timeout
    ) {
        requireProvider(provider);
        String safeModel = requireText(model, "model");
        String safeBaseUrl = requireHttpsBaseUrl(baseUrl);
        String secret = requireSecret(apiKey);
        String safeCompletionsPath = requireCompletionsPath(completionsPath);
        Duration safeTimeout = requireTimeout(timeout);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(safeTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(safeTimeout);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(safeBaseUrl)
                .apiKey(secret)
                .completionsPath(safeCompletionsPath)
                .restClientBuilder(restClientBuilder)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(safeModel)
                .temperature(0.0)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    static void requireProvider(String provider) {
        String normalized = requireText(provider, "provider").toLowerCase(Locale.ROOT);
        if (!normalized.equals("openai-compatible") && !normalized.equals("openai")) {
            throw new IllegalStateException("Unsupported cloud LLM provider");
        }
    }

    static String requireHttpsBaseUrl(String value) {
        String text = requireText(value, "base-url");
        final URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Cloud LLM base-url is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("Cloud LLM base-url must be a credential-free HTTPS origin/path");
        }
        String normalized = uri.toString();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    static String requireCompletionsPath(String value) {
        String path = requireText(value, "completions-path");
        if (!path.startsWith("/") || path.contains("..") || path.contains("?") || path.contains("#")) {
            throw new IllegalStateException("Cloud LLM completions-path is invalid");
        }
        return path;
    }

    static String requireSecret(String value) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalStateException("Cloud LLM API key is missing or invalid");
        }
        return value;
    }

    static Duration requireTimeout(String text) {
        final Duration value;
        try {
            value = DurationStyle.detectAndParse(requireText(text, "timeout"));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Cloud LLM timeout must be between 1s and 120s");
        }
        if (value == null || value.compareTo(MIN_TIMEOUT) < 0 || value.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalStateException("Cloud LLM timeout must be between 1s and 120s");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalStateException("Cloud LLM " + field + " is missing or invalid");
        }
        return value.trim();
    }
}
