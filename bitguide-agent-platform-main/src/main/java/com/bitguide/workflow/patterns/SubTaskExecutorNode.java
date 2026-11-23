package com.bitguide.workflow.patterns;

import com.bitguide.agent.Agent;
import com.bitguide.agent.AgentEntry;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes sub-tasks produced by SupervisorNode.
 * Sub-tasks run in parallel via CompletableFuture, results are merged into agent_response.
 */
public class SubTaskExecutorNode implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(SubTaskExecutorNode.class);

    private final String id;
    private final AgentRegistry registry;

    public SubTaskExecutorNode(String id, AgentRegistry registry) {
        this.id = id;
        this.registry = registry;
    }

    @Override
    public String getId() { return id; }

    @Override
    @SuppressWarnings("unchecked")
    public WorkflowState execute(WorkflowState state) {
        Object raw = state.get("sub_tasks");
        if (raw == null) {
            log.warn("SubTaskExecutorNode '{}': no sub_tasks found in state", id);
            return state;
        }

        List<?> rawList = (List<?>) raw;
        List<SubTask> subTasks = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof SupervisorNode.SubTask) {
                SupervisorNode.SubTask st = (SupervisorNode.SubTask) item;
                subTasks.add(new SubTask(st.getAgentId(), st.getDescription()));
            } else if (item instanceof java.util.Map) {
                java.util.Map<String, String> map = (java.util.Map<String, String>) item;
                subTasks.add(new SubTask(map.get("agentId"), map.get("description")));
            }
        }

        int total = subTasks.size();
        String[] results = new String[total];

        ExecutorService executor = Executors.newFixedThreadPool(total);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            final int idx = i;
            final SubTask task = subTasks.get(idx);
            futures.add(CompletableFuture.runAsync(() -> {
                log.info("SubTaskExecutorNode '{}': [{}/{}] calling agent '{}' for: {}",
                        id, idx + 1, total, task.agentId, task.description);

                AgentEntry entry = registry.acquire(task.agentId);
                try {
                    Agent agent = entry.getAgent();
                    String response = agent.chat(task.description, "wf-" + state.getExecutionId());
                    results[idx] = "【" + task.description + "】\n" + response;
                    log.info("SubTaskExecutorNode '{}': agent '{}' responded ({} chars)",
                            id, task.agentId, response.length());
                } finally {
                    registry.release(task.agentId);
                }
            }, executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
        }

        List<String> resultList = new ArrayList<>();
        for (String r : results) {
            if (r != null) resultList.add(r);
        }

        String merged = String.join("\n\n---\n\n", resultList);
        state.set("agent_response", merged);
        state.set("sub_task_results", resultList);
        return state;
    }

    static class SubTask {
        final String agentId;
        final String description;
        SubTask(String agentId, String description) {
            this.agentId = agentId;
            this.description = description;
        }
    }
}
