package com.bitguide.stage4.config;

import com.bitguide.agent.AgentRegistry;
import com.bitguide.workflow.checkpoint.CheckpointStore;
import com.bitguide.workflow.core.WorkflowExecutor;
import com.bitguide.workflow.core.WorkflowGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registry for named workflow graphs.
 * Each registered workflow can be executed by ID.
 */
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final Map<String, WorkflowGraph> workflows = new LinkedHashMap<>();
    private final AgentRegistry agentRegistry;
    private final CheckpointStore checkpointStore;

    public WorkflowRegistry(AgentRegistry agentRegistry, CheckpointStore checkpointStore) {
        this.agentRegistry = agentRegistry;
        this.checkpointStore = checkpointStore;
    }

    public WorkflowRegistry(AgentRegistry agentRegistry) {
        this(agentRegistry, null);
    }

    public void register(String workflowId, WorkflowGraph graph) {
        workflows.put(workflowId, graph);
        log.info("Registered workflow: {}", workflowId);
    }

    public WorkflowExecutor createExecutor(String workflowId) {
        WorkflowGraph graph = workflows.get(workflowId);
        if (graph == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (checkpointStore != null) {
            return new WorkflowExecutor(graph, checkpointStore);
        }
        return new WorkflowExecutor(graph);
    }

    public Set<String> getAvailableWorkflows() {
        return Collections.unmodifiableSet(workflows.keySet());
    }

    public boolean exists(String workflowId) {
        return workflows.containsKey(workflowId);
    }

    public AgentRegistry getAgentRegistry() { return agentRegistry; }
    public CheckpointStore getCheckpointStore() { return checkpointStore; }
}
