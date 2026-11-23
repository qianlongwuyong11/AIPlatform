package com.bitguide.workflow.integration;

import com.bitguide.agent.Agent;
import com.bitguide.agent.AgentEntry;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an existing Agent as a WorkflowNode.
 * Gets the Agent from Registry at execution time, so etcd dynamic config is respected.
 */
public class AgentNode implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(AgentNode.class);

    private final String id;
    private final AgentRegistry registry;
    private final String agentId;
    private final String inputKey;
    private final String outputKey;

    public AgentNode(String id, AgentRegistry registry, String agentId,
                     String inputKey, String outputKey) {
        this.id = id;
        this.registry = registry;
        this.agentId = agentId;
        this.inputKey = inputKey;
        this.outputKey = outputKey;
    }

    public AgentNode(String id, AgentRegistry registry, String agentId) {
        this(id, registry, agentId, "user_input", "agent_response");
    }

    @Override
    public String getId() { return id; }

    @Override
    public WorkflowState execute(WorkflowState state) {
        AgentEntry entry = registry.acquire(agentId);
        try {
            Agent agent = entry.getAgent();
            String input = state.get(inputKey);
            if (input == null) {
                throw new IllegalStateException(
                    "AgentNode '" + id + "': input key '" + inputKey + "' not found in state");
            }
            String response = agent.chat(input, "wf-" + state.getExecutionId());
            log.info("AgentNode '{}': agent '{}' responded ({} chars)", id, agentId, response.length());
            state.set(outputKey, response);
            return state;
        } finally {
            registry.release(agentId);
        }
    }
}
