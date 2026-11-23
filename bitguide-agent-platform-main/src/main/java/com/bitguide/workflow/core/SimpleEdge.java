package com.bitguide.workflow.core;

public class SimpleEdge implements WorkflowEdge {
    private final String from;
    private final String to;

    public SimpleEdge(String from, String to) {
        this.from = from;
        this.to = to;
    }

    @Override
    public String getFrom() { return from; }

    @Override
    public String resolveTarget(WorkflowState state) { return to; }
}
