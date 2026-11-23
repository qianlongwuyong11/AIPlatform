package com.bitguide.workflow.patterns;

import com.bitguide.workflow.core.WorkflowNode;
import com.bitguide.workflow.core.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Function;

/**
 * Uses LLM or rules to determine which agent should handle the request.
 * Sets state["routed_agent"] so a conditional edge can route accordingly.
 */
public class RouterNode implements WorkflowNode {

    private static final Logger log = LoggerFactory.getLogger(RouterNode.class);

    private final String id;
    private final Function<String, String> routerFn;
    private final Map<String, String> agentMapping;

    /**
     * @param id           node ID
     * @param routerFn     function that takes user input and returns a routing key
     * @param agentMapping maps routing keys to agent/node IDs
     */
    public RouterNode(String id, Function<String, String> routerFn,
                      Map<String, String> agentMapping) {
        this.id = id;
        this.routerFn = routerFn;
        this.agentMapping = agentMapping;
    }

    @Override
    public String getId() { return id; }

    @Override
    public WorkflowState execute(WorkflowState state) {
        String userInput = state.get("user_input");
        String routeKey = routerFn.apply(userInput);

        String targetAgent = agentMapping.getOrDefault(routeKey, "default_agent");
        state.set("routed_agent", targetAgent);
        state.set("route_key", routeKey);

        log.info("RouterNode '{}': input='{}' → routeKey='{}' → agent='{}'",
            id, userInput, routeKey, targetAgent);
        return state;
    }
}
