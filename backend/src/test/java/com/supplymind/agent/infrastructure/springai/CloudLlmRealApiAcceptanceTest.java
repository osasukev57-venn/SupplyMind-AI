package com.supplymind.agent.infrastructure.springai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-cloud gate. It is never executed by the normal regression suite. */
@EnabledIfSystemProperty(named = "supplymind.llm.real", matches = "true")
class CloudLlmRealApiAcceptanceTest {

    @Test
    void callsConfiguredCloudApiWithoutPersistingTheCredential() {
        AtomicReference<ChatResponse> response = new AtomicReference<>();
        new ApplicationContextRunner()
                .withUserConfiguration(CloudLlmConfiguration.class)
                .withPropertyValues(
                        "supplymind.agent.llm.enabled=true",
                        "supplymind.agent.llm.provider=${SUPPLYMIND_LLM_PROVIDER}",
                        "supplymind.agent.llm.model=${SUPPLYMIND_LLM_MODEL}",
                        "supplymind.agent.llm.base-url=${SUPPLYMIND_LLM_BASE_URL}",
                        "supplymind.agent.llm.api-key=${SUPPLYMIND_LLM_API_KEY}",
                        "supplymind.agent.llm.completions-path=${SUPPLYMIND_LLM_COMPLETIONS_PATH:/v1/chat/completions}",
                        "supplymind.agent.llm.timeout=${SUPPLYMIND_LLM_TIMEOUT:30s}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatModel model = context.getBean(ChatModel.class);
                    response.set(model.call(new Prompt(
                            "Reply with exactly this token and nothing else: SUPPLYMIND_CLOUD_OK")));
                });

        assertThat(response.get()).isNotNull();
        assertThat(response.get().getResult()).isNotNull();
        assertThat(response.get().getResult().getOutput().getText())
                .contains("SUPPLYMIND_CLOUD_OK");
    }
}
