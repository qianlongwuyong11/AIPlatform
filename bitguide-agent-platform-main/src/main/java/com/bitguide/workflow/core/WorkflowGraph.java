package com.bitguide.workflow.core;

import com.bitguide.workflow.core.channels.LastValueChannel;

import java.util.*;
import java.util.function.Function;

/**
 * Builder for constructing workflow graphs.
 * Usage:
 *   WorkflowGraph graph = WorkflowGraph.builder()
 *       .addNode("step1", state -> { state.set("x", 1); return state; })
 *       .addEdge(START, "step1")
 *       .addEdge("step1", END)
 *       .build();
 */
public class WorkflowGraph {

    public static final String START = "__start__";
    public static final String END = "__end__";

    private final Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
    private final List<WorkflowEdge> edges = new ArrayList<>();
    private final Map<String, Channel> channels = new HashMap<>();

    private WorkflowGraph() {}

    public static Builder builder() {
        return new Builder();
    }

    public WorkflowNode getNode(String id) {
        return nodes.get(id);
    }

    public Map<String, WorkflowNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public List<WorkflowEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    public Map<String, Channel> getChannels() {
        return Collections.unmodifiableMap(channels);
    }

    public List<WorkflowEdge> getOutgoingEdges(String nodeId) {
        List<WorkflowEdge> result = new ArrayList<>();
        for (WorkflowEdge edge : edges) {
            if (edge.getFrom().equals(nodeId)) {
                result.add(edge);
            }
        }
        return result;
    }

    public static class Builder {
        private final WorkflowGraph graph = new WorkflowGraph();

        public Builder addNode(String id, WorkflowNode node) {
            graph.nodes.put(id, node);
            return this;
        }

        public Builder addNode(String id, Function<WorkflowState, WorkflowState> fn) {
            graph.nodes.put(id, new FunctionNode(id, fn));
            return this;
        }

        public Builder addEdge(String from, String to) {
            graph.edges.add(new SimpleEdge(from, to));
            return this;
        }

        public Builder addConditionalEdge(String from, Function<WorkflowState, String> router) {
            graph.edges.add(new ConditionalEdge(from, router));
            return this;
        }

        public Builder addConditionalEdge(String from, Function<WorkflowState, String> router,
                                          Map<String, String> mapping) {
            graph.edges.add(new ConditionalEdge(from, router, mapping));
            return this;
        }

        public Builder withChannel(String key, Channel channel) {
            graph.channels.put(key, channel);
            return this;
        }

        public Builder withLastValueChannel(String key) {
            graph.channels.put(key, new LastValueChannel());
            return this;
        }

        public Builder withAppendChannel(String key) {
            graph.channels.put(key, new com.bitguide.workflow.core.channels.AppendChannel());
            return this;
        }

        public WorkflowGraph build() {
            // Validate: at least one node
            if (graph.nodes.isEmpty()) {
                throw new IllegalStateException("Graph must have at least one node");
            }
            // Validate: all edge targets exist as nodes or START/END
            for (WorkflowEdge edge : graph.edges) {
                // Edges from START must point to existing nodes
                // Edges to END are always valid
                // We validate targets at runtime when resolving
            }
            return graph;
        }
    }

    /**
     * Internal: wraps a lambda as a WorkflowNode.
     */
    private static class FunctionNode implements WorkflowNode {
        private final String id;
        private final Function<WorkflowState, WorkflowState> fn;

        FunctionNode(String id, Function<WorkflowState, WorkflowState> fn) {
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
}
