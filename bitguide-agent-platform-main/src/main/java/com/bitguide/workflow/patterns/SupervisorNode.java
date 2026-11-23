package com.bitguide.workflow.patterns;

import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiFunction;

/**
 * Supervisor pattern: analyzes the task and delegates to sub-agents.
 * Sets state["sub_tasks"] (list of tasks) and state["current_sub_task"] for the first task.
 * After all sub-tasks complete, the merger node combines results.
 */
public class SupervisorNode implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(SupervisorNode.class);

    private final String id;
    private final BiFunction<String, List<String>, List<SubTask>> plannerFn;
    private final List<String> availableAgents;

    public SupervisorNode(String id, BiFunction<String, List<String>, List<SubTask>> plannerFn,
                          List<String> availableAgents) {
        this.id = id;
        this.plannerFn = plannerFn;
        this.availableAgents = availableAgents;
    }

    @Override
    public String getId() { return id; }

    @Override
    public WorkflowState execute(WorkflowState state) {
        String userInput = state.get("user_input");

        List<SubTask> subTasks = plannerFn.apply(userInput, availableAgents);
        state.set("sub_tasks", subTasks);
        state.set("total_sub_tasks", subTasks.size());

        if (!subTasks.isEmpty()) {
            SubTask first = subTasks.get(0);
            state.set("current_sub_task", first);
            state.set("current_agent", first.agentId);
            state.set("task_description", first.description);
        }

        log.info("SupervisorNode '{}': split task into {} sub-tasks", id, subTasks.size());
        return state;
    }

    /**
     * Represents a sub-task assigned to a specific agent.
     */
    public static class SubTask {
        private final String agentId;
        private final String description;

        public SubTask(String agentId, String description) {
            this.agentId = agentId;
            this.description = description;
        }

        public String getAgentId() { return agentId; }
        public String getDescription() { return description; }

        @Override
        public String toString() {
            return "SubTask{agent='" + agentId + "', desc='" + description + "'}";
        }
    }
}
