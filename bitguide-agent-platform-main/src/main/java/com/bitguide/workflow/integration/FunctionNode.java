package com.bitguide.workflow.integration;

import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;

import java.util.function.Function;

/**
 * Wraps a simple lambda as a WorkflowNode.
 */
public class FunctionNode implements WorkflowNode {

    private final String id;
    private final Function<WorkflowState, WorkflowState> fn;

    public FunctionNode(String id, Function<WorkflowState, WorkflowState> fn) {
        this.id = id;
        this.fn = fn;
    }

    @Override
    public String getId() { return id; }

    @Override
    public WorkflowState execute(WorkflowState state) {
        return fn.apply(state);
    }
}
