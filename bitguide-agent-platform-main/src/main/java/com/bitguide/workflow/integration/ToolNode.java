package com.bitguide.workflow.integration;

import com.bitguide.tool.ToolManager;
import com.bitguide.tool.ToolResult;
import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;

import java.util.Map;

/**
 * Wraps a Tool as a WorkflowNode.
 */
public class ToolNode implements WorkflowNode {

    private final String id;
    private final ToolManager toolManager;
    private final String toolName;
    private final String inputKey;
    private final String outputKey;

    public ToolNode(String id, ToolManager toolManager, String toolName,
                    String inputKey, String outputKey) {
        this.id = id;
        this.toolManager = toolManager;
        this.toolName = toolName;
        this.inputKey = inputKey;
        this.outputKey = outputKey;
    }

    public ToolNode(String id, ToolManager toolManager, String toolName) {
        this(id, toolManager, toolName, "tool_args", "tool_result");
    }

    @Override
    public String getId() { return id; }

    @Override
    @SuppressWarnings("unchecked")
    public WorkflowState execute(WorkflowState state) {
        Object argsObj = state.get(inputKey);
        Map<String, Object> args;
        if (argsObj instanceof Map) {
            args = (Map<String, Object>) argsObj;
        } else {
            throw new IllegalStateException(
                "ToolNode '" + id + "': input key '" + inputKey + "' must be a Map");
        }
        ToolResult result = toolManager.execute(toolName, args);
        state.set(outputKey, result.getOutput());
        return state;
    }
}
