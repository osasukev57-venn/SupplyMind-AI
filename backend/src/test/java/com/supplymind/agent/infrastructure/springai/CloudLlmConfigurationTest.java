package com.supplymind.agent.infrastructure.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CloudLlmConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CloudLlmConfiguration.class);

    @Test
    void remainsDisabledWithoutCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatModel.class);
        });
    }

    @Test
    void createsConcreteCloudModelOnlyWhenExplicitlyEnabled() {
        runner.withPropertyValues(
                        "supplymind.agent.llm.enabled=true",
                        "supplymind.agent.llm.provider=openai-compatible",
                        "supplymind.agent.llm.model=test-model",
                        "supplymind.agent.llm.base-url=https://llm.example.test",
                        "supplymind.agent.llm.api-key=test-secret-not-real",
                        "supplymind.agent.llm.completions-path=/v1/chat/completions",
                        "supplymind.agent.llm.timeout=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                });
    }

    @Test
    void enabledWithoutKeyFailsClosedWithoutLeakingConfiguration() {
        runner.withPropertyValues(
                        "supplymind.agent.llm.enabled=true",
                        "supplymind.agent.llm.provider=openai-compatible",
                        "supplymind.agent.llm.model=test-model",
                        "supplymind.agent.llm.base-url=https://llm.example.test",
                        "supplymind.agent.llm.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageNotContaining("test-secret")
                            .hasRootCauseMessage("Cloud LLM API key is missing or invalid");
                });
    }

    @Test
    void rejectsHttpAndCredentialBearingUrls() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> CloudLlmConfiguration.requireHttpsBaseUrl("http://example.test")))
                .hasMessageContaining("HTTPS");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> CloudLlmConfiguration.requireHttpsBaseUrl("https://user:secret@example.test")))
                .hasMessageContaining("credential-free");
    }

    @Test
    void rejectsUnsupportedProviderAndUnsafePath() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> CloudLlmConfiguration.requireProvider("local")))
                .hasMessage("Unsupported cloud LLM provider");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> CloudLlmConfiguration.requireCompletionsPath("/v1/../admin")))
                .hasMessage("Cloud LLM completions-path is invalid");
    }

    @Test
    void rejectsDocumentationPlaceholderBracketsAroundTheKey() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> CloudLlmConfiguration.requireSecret("<sk-test-not-real>")))
                .hasMessage("Cloud LLM API key must not include placeholder brackets");
    }
}
