package com.bitguide.workflow.patterns;

import com.bitguide.workflow.checkpoint.CheckpointStore;
import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pauses workflow execution for human approval/input.
 * Saves a checkpoint with status "paused", then throws an exception to halt the executor.
 * The workflow can be resumed via WorkflowExecutor.resume() with user input.
 */
public class InterruptNode implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(InterruptNode.class);

    private final String id;
    private final String promptKey;
    private final CheckpointStore checkpointStore;

    /**
     * @param id              node ID
     * @param promptKey       state key containing the prompt/question to show the human
     * @param checkpointStore store to save the paused state
     */
    public InterruptNode(String id, String promptKey, CheckpointStore checkpointStore) {
        this.id = id;
        this.promptKey = promptKey;
        this.checkpointStore = checkpointStore;
    }

    @Override
    public String getId() { return id; }

    @Override
    public WorkflowState execute(WorkflowState state) {
        String prompt = state.get(promptKey);
        log.info("InterruptNode '{}': pausing for human input. Prompt: {}", id, prompt);

        // Save checkpoint as paused
        if (checkpointStore != null) {
            com.bitguide.workflow.checkpoint.Checkpoint cp = new com.bitguide.workflow.checkpoint.Checkpoint();
            cp.setExecutionId(state.getExecutionId());
            cp.setCurrentNodeId(id);
            cp.setStateData(state.toMap());
            cp.setStatus("paused");
            checkpointStore.save(cp);
        }

        // Signal interruption
        state.set("_interrupted", true);
        state.set("_interrupt_prompt", prompt);
        return state;
    }
}
