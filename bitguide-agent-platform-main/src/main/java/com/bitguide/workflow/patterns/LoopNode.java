package com.bitguide.workflow.patterns;

import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A node that loops its internal logic until a condition is met or max iterations reached.
 * Used to implement ReAct-style thinking loops within a workflow graph.
 */
public class LoopNode implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(LoopNode.class);

    private final String id;
    private final WorkflowNode bodyNode;
    private final Predicate<WorkflowState> shouldContinue;
    private final int maxIterations;

    /**
     * @param id            node ID
     * @param bodyNode      the node to execute in each iteration
     * @param shouldContinue predicate that returns true to continue looping
     * @param maxIterations safety limit (default 10)
     */
    public LoopNode(String id, WorkflowNode bodyNode,
                    Predicate<WorkflowState> shouldContinue, int maxIterations) {
        this.id = id;
        this.bodyNode = bodyNode;
        this.shouldContinue = shouldContinue;
        this.maxIterations = maxIterations;
    }

    public LoopNode(String id, WorkflowNode bodyNode, Predicate<WorkflowState> shouldContinue) {
        this(id, bodyNode, shouldContinue, 10);
    }

    @Override
    public String getId() { return id; }

    @Override
    public WorkflowState execute(WorkflowState state) {
        int iteration = 0;
        while (iteration < maxIterations && shouldContinue.test(state)) {
            iteration++;
            log.info("LoopNode '{}': iteration {}/{}", id, iteration, maxIterations);
            state = bodyNode.execute(state);
        }
        state.set("loop_iterations", iteration);
        log.info("LoopNode '{}': completed after {} iterations", id, iteration);
        return state;
    }
}
