package com.supplymind.agent.infrastructure.springai;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

/**
 * D6-T01 Spring AI ToolCallbackProvider: exposes exactly the seven frozen read-only Tool
 * Adapters to Spring AI tool calling (DEC-060: model selects, SupplyMind executes/validates).
 * Only these adapters are registered - nothing else in the application is ever callable by the
 * model.
 */
public final class SupplyMindToolCallbackProvider implements ToolCallbackProvider {

    private final ToolCallbackProvider delegate;

    public SupplyMindToolCallbackProvider(List<Object> adapters) {
        this.delegate = MethodToolCallbackProvider.builder()
                .toolObjects(adapters.toArray())
                .build();
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return delegate.getToolCallbacks();
    }
}
