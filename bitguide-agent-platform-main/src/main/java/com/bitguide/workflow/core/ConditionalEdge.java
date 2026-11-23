package com.bitguide.workflow.core;

import java.util.Map;
import java.util.function.Function;

/**
 * Edge that routes to different target nodes based on current state.
 * If a mapping is provided, the router function returns a key that maps to a node ID.
 * If no mapping, the router function returns the node ID directly.
 */
public class ConditionalEdge implements WorkflowEdge {
    private final String from;
    private final Function<WorkflowState, String> router;
    private final Map<String, String> mapping;

    public ConditionalEdge(String from, Function<WorkflowState, String> router) {
        this(from, router, null);
    }

    public ConditionalEdge(String from, Function<WorkflowState, String> router, Map<String, String> mapping) {
        this.from = from;
        this.router = router;
        this.mapping = mapping;
    }

    @Override
    public String getFrom() { return from; }

    @Override
    public String resolveTarget(WorkflowState state) {
        String key = router.apply(state);
        if (mapping != null && mapping.containsKey(key)) {
            return mapping.get(key);
        }
        return key;
    }
}
