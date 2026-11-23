package com.bitguide.workflow.core;

/**
 * A single step in a workflow graph.
 * Reads from state, performs work, writes back to state.
 */
public interface WorkflowNode {
    String getId();
    WorkflowState execute(WorkflowState state);
}
