package com.bitguide.stage4.config;

import com.bitguide.agent.AgentRegistry;
import com.bitguide.workflow.checkpoint.CheckpointStore;
import com.bitguide.workflow.core.WorkflowGraph;
import com.bitguide.workflow.integration.AgentNode;
import com.bitguide.workflow.patterns.InterruptNode;
import com.bitguide.workflow.patterns.RouterNode;
import com.bitguide.workflow.patterns.SubTaskExecutorNode;
import com.bitguide.workflow.patterns.SupervisorNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registers demo workflow graphs on startup.
 * Students can modify these or add new ones.
 */
@Configuration
public class DemoWorkflowConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoWorkflowConfig.class);

    private final WorkflowRegistry workflowRegistry;
    private final AgentRegistry agentRegistry;
    private final CheckpointStore checkpointStore;

    public DemoWorkflowConfig(WorkflowRegistry workflowRegistry, AgentRegistry agentRegistry) {
        this.workflowRegistry = workflowRegistry;
        this.agentRegistry = agentRegistry;
        this.checkpointStore = workflowRegistry.getCheckpointStore();
    }

    @PostConstruct
    public void init() {
        registerLinearWorkflow();
        registerRouterWorkflow();
        registerTechWorkflow();
        registerPipelineWorkflow();
        registerClassifierWorkflow();
        registerCollaborateWorkflow();
        registerInterruptWorkflow();
        registerSupervisorWorkflow();
        log.info("Demo workflows registered: {}", workflowRegistry.getAvailableWorkflows());
    }

    /**
     * Demo 1: Linear workflow — input → agent → output
     */
    private void registerLinearWorkflow() {
        WorkflowGraph graph = WorkflowGraph.builder()
            .addNode("agent", new AgentNode("agent", agentRegistry, "parenting_advisor"))
            .addEdge(WorkflowGraph.START, "agent")
            .addEdge("agent", WorkflowGraph.END)
            .build();

        workflowRegistry.register("linear-demo", graph);
    }

    /**
     * Demo 2: Router workflow — route to different agents based on input
     */
    private void registerRouterWorkflow() {
        // Simple keyword-based router for demo.
        // In production, this could call LLM for intent recognition.
        RouterNode router = new RouterNode("router",
            input -> {
                if (input != null) {
                    if (input.contains("计算") || input.contains("算")) return "tech";
                    if (input.contains("育儿") || input.contains("孩子")) return "parenting";
                }
                return "default";
            },
            Map.of(
                "tech", "agent_tech",
                "parenting", "agent_parenting",
                "default", "agent_default"
            )
        );

        WorkflowGraph graph = WorkflowGraph.builder()
            .addNode("router", router)
            .addNode("agent_tech", new AgentNode("agent_tech", agentRegistry, "parenting_advisor"))
            .addNode("agent_parenting", new AgentNode("agent_parenting", agentRegistry, "parenting_advisor"))
            .addNode("agent_default", new AgentNode("agent_default", agentRegistry, "parenting_advisor"))
            .addEdge(WorkflowGraph.START, "router")
            .addConditionalEdge("router", state -> state.get("routed_agent"))
            .addEdge("agent_tech", WorkflowGraph.END)
            .addEdge("agent_parenting", WorkflowGraph.END)
            .addEdge("agent_default", WorkflowGraph.END)
            .build();

        workflowRegistry.register("router-demo", graph);
    }

    /**
     * Demo 3: Tech helper — linear workflow with a different agent
     */
    private void registerTechWorkflow() {
        WorkflowGraph graph = WorkflowGraph.builder()
                .addNode("agent", new AgentNode("agent", agentRegistry, "tech_helper"))
                .addEdge(WorkflowGraph.START, "agent")
                .addEdge("agent", WorkflowGraph.END)
                .build();
        workflowRegistry.register("tech-demo", graph);
    }

    /**
     * Demo 4: Pipeline — preprocess → agent → postprocess
     */
    private void registerPipelineWorkflow() {
        WorkflowGraph graph = WorkflowGraph.builder()
                .addNode("preprocess", state -> {
                    String input = state.get("user_input");
                    String modified = "[工作流预处理] " + input;
                    log.info("  preprocess: '{}' → '{}'", input, modified);
                    state.set("user_input", modified);
                    return state;
                })
                .addNode("agent", new AgentNode("agent", agentRegistry, "parenting_advisor"))
                .addNode("postprocess", state -> {
                    String response = state.get("agent_response");
                    state.set("agent_response", "📝 " + response + "\n\n--- 由工作流引擎生成 ---");
                    return state;
                })
                .addEdge(WorkflowGraph.START, "preprocess")
                .addEdge("preprocess", "agent")
                .addEdge("agent", "postprocess")
                .addEdge("postprocess", WorkflowGraph.END)
                .build();
        workflowRegistry.register("pipeline-demo", graph);
    }

    /**
     * Demo 5: Classifier — custom intent routing with FunctionNode + ConditionalEdge
     */
    private void registerClassifierWorkflow() {
        WorkflowGraph graph = WorkflowGraph.builder()
                .addNode("classifier", state -> {
                    String input = state.get("user_input");
                    String intent;
                    if (input.contains("代码") || input.contains("程序") || input.contains("bug")) {
                        intent = "tech";
                    } else if (input.contains("孩子") || input.contains("宝宝") || input.contains("育儿")) {
                        intent = "parenting";
                    } else {
                        intent = "default";
                    }
                    state.set("intent", intent);
                    return state;
                })
                .addNode("tech_agent", new AgentNode("tech_agent", agentRegistry, "tech_helper"))
                .addNode("parenting_agent", new AgentNode("parenting_agent", agentRegistry, "parenting_advisor"))
                .addNode("default_agent", new AgentNode("default_agent", agentRegistry, "parenting_advisor"))
                .addEdge(WorkflowGraph.START, "classifier")
                .addConditionalEdge("classifier", state -> {
                    String intent = state.get("intent");
                    switch (intent) {
                        case "tech": return "tech_agent";
                        case "parenting": return "parenting_agent";
                        default: return "default_agent";
                    }
                })
                .addEdge("tech_agent", WorkflowGraph.END)
                .addEdge("parenting_agent", WorkflowGraph.END)
                .addEdge("default_agent", WorkflowGraph.END)
                .build();
        workflowRegistry.register("classifier-demo", graph);
    }

    /**
     * Demo 6: Two-agent collaboration — draft + review
     */
    private void registerCollaborateWorkflow() {
        WorkflowGraph graph = WorkflowGraph.builder()
                // Agent 1: 育儿顾问生成初稿，输出到 "draft"
                .addNode("drafter", new AgentNode("drafter", agentRegistry,
                        "parenting_advisor", "user_input", "draft"))
                // Agent 2: 内容编辑审阅润色，从 "draft" 读入，输出到 "agent_response"
                .addNode("reviewer", new AgentNode("reviewer", agentRegistry,
                        "content_editor", "draft", "agent_response"))
                .addEdge(WorkflowGraph.START, "drafter")
                .addEdge("drafter", "reviewer")
                .addEdge("reviewer", WorkflowGraph.END)
                .build();
        workflowRegistry.register("collab-demo", graph);
    }

    /**
     * Demo 7: Interrupt — Agent初稿 → 人工确认 → Agent润色
     */
    private void registerInterruptWorkflow() {
        AgentNode draftNode = new AgentNode("draft_agent", agentRegistry,
                "parenting_advisor", "user_input", "draft");

        InterruptNode interruptNode = new InterruptNode("human_review", "draft", checkpointStore);

        AgentNode refineNode = new AgentNode("refine_agent", agentRegistry,
                "content_editor", "draft", "agent_response");

        WorkflowGraph graph = WorkflowGraph.builder()
                .addNode("draft_agent", draftNode)
                .addNode("human_review", interruptNode)
                .addNode("refine_agent", refineNode)
                .addEdge(WorkflowGraph.START, "draft_agent")
                .addEdge("draft_agent", "human_review")
                .addEdge("human_review", "refine_agent")
                .addEdge("refine_agent", WorkflowGraph.END)
                .build();
        workflowRegistry.register("interrupt-demo", graph);
    }

    /**
     * Demo 8: Supervisor — supervisor splits task → sub-tasks executed by agents → merged output
     */
    private void registerSupervisorWorkflow() {
        List<String> agents = List.of("parenting_advisor", "tech_helper");

        SupervisorNode supervisor = new SupervisorNode("supervisor",
                (userInput, availableAgents) -> {
                    List<SupervisorNode.SubTask> tasks = new ArrayList<>();
                    tasks.add(new SupervisorNode.SubTask("parenting_advisor",
                            "请从育儿角度分析：" + userInput));
                    tasks.add(new SupervisorNode.SubTask("tech_helper",
                            "请从技术角度给出解决方案：" + userInput));
                    return tasks;
                },
                agents);

        SubTaskExecutorNode executor = new SubTaskExecutorNode("sub_executor", agentRegistry);

        WorkflowGraph graph = WorkflowGraph.builder()
                .addNode("supervisor", supervisor)
                .addNode("sub_executor", executor)
                .addEdge(WorkflowGraph.START, "supervisor")
                .addEdge("supervisor", "sub_executor")
                .addEdge("sub_executor", WorkflowGraph.END)
                .build();
        workflowRegistry.register("supervisor-demo", graph);
    }
}
