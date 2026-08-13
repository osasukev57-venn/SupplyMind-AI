package com.supplymind.agent.tool;

/**
 * D6-T01 structured tool input failure. Converted by adapters into a REJECTED ToolResult;
 * never surfaces as a stack trace to the LLM.
 */
public final class ToolInputException extends RuntimeException {
    private final String toolName;

    public ToolInputException(String toolName, String message) {
        super(message);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
