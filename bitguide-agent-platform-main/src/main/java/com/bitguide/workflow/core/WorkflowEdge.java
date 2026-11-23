package com.bitguide.workflow.core;

/**
 * Directed edge between nodes. Determines which node to execute next.
 */
public interface WorkflowEdge {
    String getFrom();
    String resolveTarget(WorkflowState state);
}
