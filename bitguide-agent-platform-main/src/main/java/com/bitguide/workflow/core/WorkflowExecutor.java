package com.bitguide.workflow.core;

import com.bitguide.workflow.checkpoint.Checkpoint;
import com.bitguide.workflow.checkpoint.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes a workflow graph. Serial execution with checkpoint support.
 */
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final WorkflowGraph graph;
    private final CheckpointStore checkpointStore;

    public WorkflowExecutor(WorkflowGraph graph) {
        this(graph, null);
    }

    public WorkflowExecutor(WorkflowGraph graph, CheckpointStore checkpointStore) {
        this.graph = graph;
        this.checkpointStore = checkpointStore;
    }

    /**
     * Run the workflow from start to end.
     * If an InterruptNode sets _interrupted=true, execution pauses and returns.
     */
    public WorkflowState run(WorkflowState initialState, String workflowId) {
        String executionId = UUID.randomUUID().toString().substring(0, 8);
        initialState.setExecutionId(executionId);
        initialState.set("_workflow_id", workflowId);

        // Find start target
        String currentNodeId = resolveStartTarget(initialState);
        log.info("[Workflow:{}] Starting execution, first node: {}", executionId, currentNodeId);

        WorkflowState state = initialState;

        while (currentNodeId != null && !WorkflowGraph.END.equals(currentNodeId)) {
            WorkflowNode node = graph.getNode(currentNodeId);
            if (node == null) {
                throw new IllegalStateException(
                    "Node '" + currentNodeId + "' not found in graph");
            }

            log.info("[Workflow:{}] Executing node: {}", executionId, currentNodeId);
            state.setCurrentNodeId(currentNodeId);
            state = node.execute(state);

            // 检查中断标志
            if (Boolean.TRUE.equals(state.get("_interrupted"))) {
                if (checkpointStore != null) {
                    saveCheckpoint(executionId, currentNodeId, state, "paused");
                }
                log.info("[Workflow:{}] Paused at node '{}' for human input", executionId, currentNodeId);
                return state;
            }

            // Save checkpoint
            if (checkpointStore != null) {
                saveCheckpoint(executionId, currentNodeId, state, "running");
            }

            // Find next node
            currentNodeId = resolveNextNode(currentNodeId, state);
        }

        // Mark completed
        if (checkpointStore != null) {
            saveCheckpoint(executionId, currentNodeId, state, "completed");
        }

        log.info("[Workflow:{}] Execution completed", executionId);
        return state;
    }

    /**
     * Resume a paused workflow from checkpoint.
     */
    public WorkflowState resume(String executionId, Map<String, Object> userInput) {
        if (checkpointStore == null) {
            throw new IllegalStateException("No checkpoint store configured");
        }

        Checkpoint checkpoint = checkpointStore.load(executionId);
        if (checkpoint == null) {
            throw new IllegalArgumentException("Checkpoint not found: " + executionId);
        }
        if (!"paused".equals(checkpoint.getStatus())) {
            throw new IllegalStateException("Workflow is not paused, status: " + checkpoint.getStatus());
        }

        // Restore state
        WorkflowState state = new WorkflowState(graph.getChannels());
        for (Map.Entry<String, Object> entry : checkpoint.getStateData().entrySet()) {
            state.set(entry.getKey(), entry.getValue());
        }
        state.setExecutionId(executionId);

        // 清除中断标志（从 checkpoint 恢复时会带 _interrupted=true）
        state.set("_interrupted", false);
        state.set("_interrupt_prompt", null);

        // Merge user input
        if (userInput != null) {
            for (Map.Entry<String, Object> entry : userInput.entrySet()) {
                state.set(entry.getKey(), entry.getValue());
            }
        }

        // Continue from the next node after the paused node
        String currentNodeId = resolveNextNode(checkpoint.getCurrentNodeId(), state);
        log.info("[Workflow:{}] Resuming from node: {}", executionId, currentNodeId);

        while (currentNodeId != null && !WorkflowGraph.END.equals(currentNodeId)) {
            WorkflowNode node = graph.getNode(currentNodeId);
            if (node == null) {
                throw new IllegalStateException("Node '" + currentNodeId + "' not found");
            }

            log.info("[Workflow:{}] Executing node: {}", executionId, currentNodeId);
            state.setCurrentNodeId(currentNodeId);
            state = node.execute(state);

            // 检查中断标志
            if (Boolean.TRUE.equals(state.get("_interrupted"))) {
                if (checkpointStore != null) {
                    saveCheckpoint(executionId, currentNodeId, state, "paused");
                }
                log.info("[Workflow:{}] Paused at node '{}' for human input", executionId, currentNodeId);
                return state;
            }

            if (checkpointStore != null) {
                saveCheckpoint(executionId, currentNodeId, state, "running");
            }

            currentNodeId = resolveNextNode(currentNodeId, state);
        }

        if (checkpointStore != null) {
            saveCheckpoint(executionId, currentNodeId, state, "completed");
        }

        log.info("[Workflow:{}] Execution completed after resume", executionId);
        return state;
    }

    private String resolveStartTarget(WorkflowState state) {
        List<WorkflowEdge> startEdges = graph.getOutgoingEdges(WorkflowGraph.START);
        if (startEdges.isEmpty()) {
            throw new IllegalStateException("No edges from START");
        }
        // Take the first matching edge
        return startEdges.get(0).resolveTarget(state);
    }

    private String resolveNextNode(String fromNode, WorkflowState state) {
        List<WorkflowEdge> outgoing = graph.getOutgoingEdges(fromNode);
        if (outgoing.isEmpty()) {
            // No outgoing edges means end
            return WorkflowGraph.END;
        }
        if (outgoing.size() == 1) {
            return outgoing.get(0).resolveTarget(state);
        }
        // Multiple edges: evaluate each (conditional edges check their condition)
        for (WorkflowEdge edge : outgoing) {
            String target = edge.resolveTarget(state);
            if (target != null) {
                return target;
            }
        }
        return WorkflowGraph.END;
    }

    private void saveCheckpoint(String executionId, String currentNodeId,
                                WorkflowState state, String status) {
        Checkpoint cp = new Checkpoint();
        cp.setExecutionId(executionId);
        cp.setCurrentNodeId(currentNodeId);
        cp.setStateData(state.toMap());
        cp.setStatus(status);
        checkpointStore.save(cp);
    }
}
