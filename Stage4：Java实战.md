# 第11章 Java 实战（Stage 4）—— 工作流引擎与多 Agent 协作

第7章我们跑通了 Stage 1-3，这一章来跑 Stage 4。三个目标：**理解工作流引擎的设计**、**动手跑通演示**、**学会自定义工作流**。

> **前置条件**：确保你已经完成了第7章的环境搭建（JDK 17+、Maven、Ollama、qwen2.5 模型）。Stage 4 只需要 Stage 1 的基础环境，不需要 Docker 和 etcd。

---

> 下载代码：包含stage4的代码[Java（选最新版本）](https://ecnz1eq1tqym.feishu.cn/wiki/FvoiwuGV6i79b2ke78JclJnqnBf)

## 第一部分：跑起来看效果

### 11.1 启动 Stage 4

#### 确保 Ollama 在运行

```Bash
# 检查 Ollama 状态
curl http://localhost:11434/api/tags

# 如果没有响应，启动 Ollama（新终端窗口）
ollama serve
```

#### 编译项目

```Bash
cd bitguide-agent-platform
mvn compile
```

#### 启动 Stage 4（CLI 模式）

```Bash
mvn exec:java -Dexec.mainClass="com.bitguide.stage4.Stage4App"
```

你应该看到：

```Plaintext
+=========================================+
|   BitGuide Stage 4 - Workflow CLI       |
|   Orchestration - Collaboration - State  |
+=========================================+

Available workflows:
  1. linear-demo
  2. router-demo
Select (enter number or name):
```

> **看到这个界面说明启动成功了！** Stage 4 默认是 CLI 模式，不需要启动 HTTP 服务器。

### 11.2 跑通线性工作流

选择 `1`（linear-demo），然后输入问题：

```Plaintext
Select (enter number or name): 1

[ok] Selected workflow: linear-demo
Type your question to start (quit to exit, list to see workflows, switch to change)
-------------------------------------------------------------------------

You: 孩子不爱吃饭怎么办

[Workflow executing...]
[Workflow:xxxx] Starting execution, first node: agent
[Workflow:xxxx] Executing node: agent
AgentNode 'agent': agent 'parenting_advisor' responded (xxx chars)
[Workflow:xxxx] Execution completed

Agent: 作为育儿顾问，我建议您可以从以下几个方面来应对...

Time: 3200ms | Execution ID: xxxx
```

**这就是最简单的工作流**：用户输入 -> Agent 处理 -> 输出结果。只有一个节点，但已经展示了工作流引擎的核心流程：State 传递 -> Node 执行 -> 输出。

### 11.3 跑通路由工作流

输入 `switch` 切换到 router-demo：

```Plaintext
You: switch

Available workflows:
  1. linear-demo
  2. router-demo
Select (enter number or name): 2

[ok] Switched to workflow: router-demo

You: 我的孩子3岁了，不爱吃蔬菜

[Workflow executing...]
RouterNode 'router': input='我的孩子3岁了，不爱吃蔬菜' -> routeKey='parenting' -> agent='agent_parenting'
[Workflow:xxxx] Starting execution, first node: router
[Workflow:xxxx] Executing node: router
[Workflow:xxxx] Executing node: agent_parenting

Agent: 3岁宝宝不爱吃蔬菜是很常见的现象...
```

**这里多了一步路由**：Router 节点先判断意图，然后把请求路由到合适的 Agent。这就是条件边的实际效果。

### 11.4 退出

```Plaintext
You: quit
Goodbye!
```

---

## 第二部分：理解代码

### 11.5 项目结构

Stage 4 的代码分两部分：

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| `src/main/java/com/bitguide/workflow/` | 工作流引擎核心 | 通用引擎，与具体 Stage 无关 |
| `src/stage4/java/com/bitguide/stage4/` | Stage 4 应用层 | 入口、配置、演示工作流、HTTP API |

引擎核心目录结构：

```Plaintext
src/main/java/com/bitguide/workflow/
├── core/                     ← 核心抽象
│   ├── WorkflowGraph.java    ← 图定义（Builder 模式）
│   ├── WorkflowNode.java     ← 节点接口
│   ├── WorkflowEdge.java     ← 边接口
│   ├── SimpleEdge.java       ← 普通边
│   ├── ConditionalEdge.java  ← 条件边
│   ├── WorkflowState.java    ← 共享状态（黑板模式）
│   ├── WorkflowExecutor.java ← 执行器（串行执行 + Checkpoint）
│   ├── Channel.java          ← Channel 接口（合并语义）
│   └── channels/
│       ├── LastValueChannel.java  ← 保留最后一个值
│       └── AppendChannel.java     ← 追加所有值
├── patterns/                 ← 预置模式节点
│   ├── RouterNode.java       ← 路由节点（关键词/规则匹配）
│   ├── SupervisorNode.java   ← 监督者节点
│   ├── LoopNode.java         ← 循环节点
│   └── InterruptNode.java    ← 中断节点（人机协作）
├── integration/              ← 与 Agent 平台的集成节点
│   ├── AgentNode.java        ← 包装 Agent 为工作流节点
│   ├── FunctionNode.java     ← 包装 Lambda 为工作流节点
│   └── ToolNode.java         ← 包装工具为工作流节点
└── checkpoint/               ← 持久化（Checkpoint）
    ├── Checkpoint.java       ← 检查点数据
    ├── CheckpointStore.java  ← 存储接口
    └── RedisCheckpointStore.java  ← Redis 实现
    └── MemoryCheckpointStore.java ← 内存实现（开发用）
```

Stage 4 应用层：

```Plaintext
src/stage4/java/com/bitguide/stage4/
├── Stage4App.java                ← 入口（CLI 模式）
├── cli/
│   └── WorkflowCli.java         ← 交互式 CLI（支持断点续跑）
├── config/
│   ├── DemoWorkflowConfig.java  ← 演示工作流定义
│   ├── WorkflowRegistry.java    ← 工作流注册表
│   └── stage4.yml               ← 配置文件
```

### 11.6 工作流是怎么定义的

打开 `DemoWorkflowConfig.java`，看线性工作流的定义：

```Java
// 线性工作流：用户输入 → Agent → 输出
WorkflowGraph graph = WorkflowGraph.builder()
    .addNode("agent", new AgentNode("agent", agentRegistry, "parenting_advisor"))
    .addEdge(WorkflowGraph.START, "agent")
    .addEdge("agent", WorkflowGraph.END)
    .build();
```

逐行解读：

- `addNode("agent", ...)` -- 添加一个节点，ID 是 "agent"，内容是调用 parenting_advisor Agent

- `addEdge(START, "agent")` -- 从起点连到 agent 节点

- `addEdge("agent", END)` -- agent 节点连到终点

路由工作流稍微复杂：

```Java
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
```

关键点：

- `addConditionalEdge` -- 条件边，根据 State 中的 `routed_agent` 字段决定走哪条路

- RouterNode 的路由函数就是简单的关键词匹配（演示用，实际可以用 LLM 做意图识别）

- RouterNode 执行时会把路由结果写入 `state["routed_agent"]`，条件边读这个值来决定走向

### 11.7 AgentNode 怎么和动态配置配合

打开 `AgentNode.java`，核心代码：

```Java
@Override
public WorkflowState execute(WorkflowState state) {
    AgentEntry entry = registry.acquire(agentId);  // 从 Registry 获取 Agent
    try {
        Agent agent = entry.getAgent();
        String input = state.get(inputKey);         // 从 State 读取输入
        String response = agent.chat(input, "wf-" + state.getExecutionId());
        state.set(outputKey, response);             // 结果写回 State
        return state;
    } finally {
        registry.release(agentId);                  // 引用计数释放
    }
}
```

**关键**：AgentNode 不直接创建 Agent，而是从 `AgentRegistry` 获取。而 Registry 里的 Agent 是由配置（YAML 或 etcd）动态管理的。所以：

- 配置改了 Agent 的 prompt -> 下次执行 AgentNode 自动用新 prompt

- 配置改了 Agent 的 tools -> 下次执行 AgentNode 自动用新 tools

- 图结构不变，行为动态

AgentNode 默认的 `inputKey` 是 `"user_input"`，`outputKey` 是 `"agent_response"`，所以前面的 CLI 示例中 Agent 回复能正确输出。

### 11.8 WorkflowState —— 黑板模式

`WorkflowState` 是工作流中所有节点共享的数据容器，采用黑板模式（Blackboard Pattern）：

```Java
// 写入
state.set("user_input", "孩子不爱吃饭");
state.set("agent_response", response);

// 读取
String input = state.get("user_input");

// Channel 控制：同一个 key 被多个节点写入时的合并策略
// LastValueChannel: 保留最后一个值（默认行为）
// AppendChannel: 追加所有值（适合收集多个节点的输出）
```

### 11.9 WorkflowExecutor —— 执行引擎

执行器负责按图结构串行执行所有节点：

```Java
public WorkflowState run(WorkflowState initialState) {
    String executionId = UUID.randomUUID().toString().substring(0, 8);
    String currentNodeId = resolveStartTarget(initialState);

    while (currentNodeId != null && !WorkflowGraph.END.equals(currentNodeId)) {
        WorkflowNode node = graph.getNode(currentNodeId);
        state = node.execute(state);           // 执行节点

        // Checkpoint 持久化（如果配置了 Redis）
        if (checkpointStore != null) {
            saveCheckpoint(executionId, currentNodeId, state, "running");
        }

        currentNodeId = resolveNextNode(currentNodeId, state);  // 找下一个节点
    }
    return state;
}
```

核心流程：找到起点 -> 依次执行节点 -> 条件边决定分支 -> 到达终点返回。每执行完一个节点，如果配置了 CheckpointStore（如 Redis），就把当前状态持久化，支持中断恢复。

---

## 第三部分：动手练习

> 三个练习逐步递进：练习一添加新 Agent + 线性工作流，练习二在 Agent 前后加处理节点，练习三组合条件边实现自定义路由。每个练习都有具体的代码改动和验证步骤。

### 11.10 练习一：添加新 Agent 和线性工作流

**目标**：创建一个"技术助手"Agent，注册一个新的 `tech-demo` 工作流。

**步骤 1** — 打开 `src/stage4/java/com/bitguide/stage4/config/stage4.yml`，在 `agents:` 下添加新 Agent：

```YAML
agents:
  parenting_advisor:
    # ... 已有，不动 ...
  tech_helper:                          # ← 新增开始
    id: tech_helper
    name: 技术助手
    description: 回答技术问题
    modelRef: ollama
    systemPrompt: "你是一个技术助手，用简洁的方式回答技术问题。"
    toolRefs:
      - calculator
    memory:
      enabled: true
      maxHistoryLength: 20              # ← 新增结束
```

**步骤 2** — 打开 `src/stage4/java/com/bitguide/stage4/config/DemoWorkflowConfig.java`，做两处修改：

第一处：`init()` 方法中添加注册调用：

```Java
@PostConstruct
public void init() {
    registerRouterWorkflow();
    registerLinearWorkflow();
    registerTechWorkflow();              // ← 新增这行
    log.info("Demo workflows registered: {}", workflowRegistry.getAvailableWorkflows());
}
```

第二处：在类的末尾（`registerRouterWorkflow` 方法之后）添加新方法：

```Java
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
```

**步骤 3** — 编译运行：

```Bash
mvn compile && mvn exec:java -Dexec.mainClass="com.bitguide.stage4.Stage4App"
```

**验证**：启动后应该看到 3 个工作流（linear-demo、router-demo、tech-demo）。选择 `tech-demo`，输入 `Java和Go有什么区别`，确认 Agent 以技术风格回答。

```Plaintext
Available workflows:
  1. linear-demo
  2. router-demo
  3. tech-demo
Select (enter number or name): 3

You: Java和Go有什么区别

Agent: Java和Go的主要区别：1. 编译方式不同...
```

### 11.11 练习二：三节点流水线（前处理 → Agent → 后处理）

**目标**：在 Agent 调用前后各加一个处理节点，体验多节点流水线。`addNode(id, lambda)` 会自动包装为 `FunctionNode`。

**步骤 1** — 在 `DemoWorkflowConfig.java` 的 `init()` 中再注册一个：

```Java
@PostConstruct
public void init() {
    registerRouterWorkflow();
    registerLinearWorkflow();
    registerTechWorkflow();
    registerPipelineWorkflow();          // ← 新增这行
    log.info("Demo workflows registered: {}", workflowRegistry.getAvailableWorkflows());
}
```

**步骤 2** — 添加 `registerPipelineWorkflow` 方法：

```Java
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
```

**步骤 3** — 编译运行：

```Bash
mvn compile && mvn exec:java -Dexec.mainClass="com.bitguide.stage4.Stage4App"
```

**验证**：选择 `pipeline-demo`，输入 `孩子不爱吃饭`。确认：

1. 日志中出现：`preprocess: '孩子不爱吃饭' → '[工作流预处理] 孩子不爱吃饭'`

1. 最终输出以 `📝` 开头，结尾有 `--- 由工作流引擎生成 ---`

```Plaintext
You: 孩子不爱吃饭

  preprocess: '孩子不爱吃饭' → '[工作流预处理] 孩子不爱吃饭'

Agent: 📝 作为育儿顾问，我建议您可以从以下几个方面来应对...

--- 由工作流引擎生成 ---
```

如果看到 preprocess 日志和 `📝` 标记，说明三个节点都正确执行了。

> **理解要点**：`preprocess` 节点修改了 `state["user_input"]`，`agent` 节点从同一个 key 读输入，`postprocess` 节点修改了 `state["agent_response"]`。三个节点通过 WorkflowState（黑板模式）传递数据。

### 11.12 练习三：自定义意图分类 + 条件路由

**目标**：用 FunctionNode 实现意图分类器，配合 `addConditionalEdge` 路由到不同 Agent。不使用 RouterNode，纯手动实现路由逻辑。

**前置**：需要练习一的 `tech_helper` Agent 已添加到 `stage4.yml`。

**步骤 1** — 在 `init()` 中注册：

```Java
registerClassifierWorkflow();           // ← 新增这行
```

**步骤 2** — 添加方法：

```Java
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
```

**步骤 3** — 编译运行：

```Bash
mvn compile && mvn exec:java -Dexec.mainClass="com.bitguide.stage4.Stage4App"
```

**验证**：选择 `classifier-demo`，分别测试三种意图：

```Plaintext
# 测试 1：育儿类问题 → 路由到 parenting_agent
You: 我的孩子3岁了很叛逆
Agent: 3岁孩子出现叛逆行为是正常的心理发展阶段...（育儿顾问风格）

# 测试 2：技术类问题 → 路由到 tech_agent
You: 我的程序有个bug找不到原因
Agent: 调试bug建议按以下步骤排查...（技术助手风格）

# 测试 3：其他问题 → 路由到 default_agent
You: 今天天气怎么样
Agent: 关于天气的问题...（默认育儿顾问回答）
```

> **理解要点**：`classifier` 节点把分类结果写入 `state["intent"]`，`addConditionalEdge` 读取这个值决定走哪个分支。这就是条件边的核心机制——读 State、返回目标节点 ID。

### 11.13 练习四：两个 Agent 串联协作（初稿 + 润色）

**目标**：让两个 Agent 串联工作——第一个 Agent 生成初稿，第二个 Agent 审阅优化。这是最典型的多 Agent 协作模式。

**步骤 1** — 在 `stage4.yml` 的 `agents:` 下添加"内容编辑"Agent：

```YAML
  content_editor:                     # ← 新增
    id: content_editor
    name: 内容编辑
    description: 审阅和优化育儿建议
    modelRef: ollama
    systemPrompt: "你是一位资深育儿内容编辑。你会收到一份育儿建议的初稿，请从以下三个方面进行优化后输出完整内容：1. 补充专业依据（如儿童心理学原理）；2. 整理为清晰的要点列表；3. 语言更加温暖、富有同理心。"
    memory:
      enabled: false
```

**步骤 2** — 在 `DemoWorkflowConfig.java` 的 `init()` 中注册：

```Java
registerCollaborateWorkflow();         // ← 新增这行
```

**步骤 3** — 添加方法。关键是 AgentNode 的四参数构造器 `(id, registry, agentId, inputKey, outputKey)`，让第二个 Agent 从"初稿 key"读、写到"终稿 key"：

```Java
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
```

**步骤 4** — 编译运行：

```Bash
mvn compile && mvn exec:java -Dexec.mainClass="com.bitguide.stage4.Stage4App"
```

**验证**：选择 `collab-demo`，输入 `孩子挑食怎么办`。你会看到两个 Agent 先后执行，最终输出的是经过润色的内容：

```Plaintext
You: 孩子挑食怎么办

[Workflow:xxxx] Executing node: drafter
AgentNode 'drafter': agent 'parenting_advisor' responded (xxx chars)
[Workflow:xxxx] Executing node: reviewer
AgentNode 'reviewer': agent 'content_editor' responded (xxx chars)

Agent: （经过编辑润色后的结构化建议，带有要点列表和专业补充）
```

> **理解要点**：这个工作流的核心是 State 做桥梁——`drafter` 把初稿写到 `state["draft"]`，`reviewer` 从 `state["draft"]` 读入初稿并润色后写到 `state["agent_response"]`。两个 Agent 通过 State 传递数据，各司其职。

### 11.14 练习五：人工中断与断点续跑

**目标**：体验工作流的"暂停 → 人工确认 → 恢复"机制。InterruptNode 会让工作流暂停，等待你输入补充意见后继续执行。

**前置**：需要练习四的 `content_editor` Agent 已添加到 `stage4.yml`。

**步骤 1** — 在 `DemoWorkflowConfig.java` 的 `init()` 中注册：

```Java
registerInterruptWorkflow();           // ← 新增这行
```

**步骤 2** — 添加方法。流程：`用户输入 → Agent生成初稿 → InterruptNode暂停 → 人工确认 → Agent润色 → 输出`

```Java
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
```

**步骤 3** — 编译运行：

```Bash
mvn compile && mvn exec:java -Dexec.mainClass="com.bitguide.stage4.Stage4App"
```

**验证**：选择 `interrupt-demo`，输入 `孩子挑食怎么办`：

```Plaintext
You: 孩子挑食怎么办

[Workflow:xxxx] Executing node: draft_agent
AgentNode 'draft_agent': agent 'parenting_advisor' responded (xxx chars)
[Workflow:xxxx] Paused at node 'human_review' for human input

--- 人工确认 ---
Agent 初稿: 作为育儿顾问，我建议您可以从以下几个方面来应对...
请输入补充要求（直接回车确认继续）: 请增加关于营养均衡的建议

[工作流恢复执行...]
[Workflow:xxxx] Executing node: refine_agent
AgentNode 'refine_agent': agent 'content_editor' responded (xxx chars)

Agent: （经过编辑润色后的结构化建议，包含营养均衡的专业补充）
```

工作流在 `human_review` 节点暂停，等你输入后自动恢复。如果直接回车，会直接使用初稿进行润色。

> **理解要点**：InterruptNode 通过在 State 中设置 `_interrupted=true` 让执行器暂停，同时保存 Checkpoint（断点）。CLI 检测到暂停后提示用户输入，然后调用 `executor.resume()` 从断点恢复执行。这就是 LangGraph 风格的"人机协作"模式。

### 11.15 练习六：Supervisor 多 Agent 任务分发

**目标**：用 SupervisorNode 把一个任务拆成多个子任务，分发给不同的 Agent 并行执行，最后合并结果。这是"主管-执行者"模式——Supervisor 负责拆任务，SubTaskExecutorNode 负责逐个执行。

**前置**：需要练习一的 `tech_helper` Agent 已添加到 `stage4.yml`。

**步骤 1** — 在 `DemoWorkflowConfig.java` 的 `init()` 中注册：

```Java
registerSupervisorWorkflow();           // ← 新增这行
```

**步骤 2** — 添加方法。流程：`用户输入 → SupervisorNode（拆任务）→ SubTaskExecutorNode（逐个执行）→ 合并输出`

```Java
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
```

**步骤 3** — 编译运行：

```Bash
mvn compile && mvn exec:java -Dexec.mainClass="com.bitguide.stage4.Stage4App"
```

**验证**：选择 `supervisor-demo`，输入 `孩子沉迷手机怎么办`：

```Plaintext
You: 孩子沉迷手机怎么办

[Workflow:xxxx] Executing node: supervisor
SupervisorNode 'supervisor': split task into 2 sub-tasks
[Workflow:xxxx] Executing node: sub_executor
SubTaskExecutorNode 'sub_executor': [1/2] calling agent 'parenting_advisor' for: 请从育儿角度分析：孩子沉迷手机怎么办
SubTaskExecutorNode 'sub_executor': [2/2] calling agent 'tech_helper' for: 请从技术角度给出解决方案：孩子沉迷手机怎么办

Agent: 【请从育儿角度分析：孩子沉迷手机怎么办】
作为育儿顾问，建议从理解孩子需求出发...

---

【请从技术角度给出解决方案：孩子沉迷手机怎么办】
从技术角度，可以采用以下方案...
```

你会看到两个 Agent 各自从自己的角度回答了同一个问题，最终结果被合并输出。

> **理解要点**：SupervisorNode 通过 `plannerFn` 把用户输入拆成子任务列表，SubTaskExecutorNode 逐个读取子任务并调用对应 Agent。这个模式适合"多角度分析"或"多步骤任务"的场景——一个主管规划，多个执行者干活。

---

## 常见问题

| 问题 | 原因 | 解决 |
| --- | --- | --- |
| 修改了代码但运行后看不到新工作流 | IDEA 使用了旧的编译结果 | 回到项目根目录执行 `mvn compile`，再重新运行程序 |
| `No workflows available` | Agent 未注册 | 检查 `stage4.yml` 中 `agents` 配置是否正确 |
| `Agent 'xxx' not found` | Agent ID 不匹配 | 确认 AgentNode 的 agentId 和 yml 中的 agent id 一致 |
| `Connection refused: localhost:11434` | Ollama 未启动 | 新终端执行 `ollama serve` |
| 中文乱码 | 终端编码问题 | 设置 `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` |

---

## 本章小结

| 你学到了什么 | 对应的概念 |
| --- | --- |
| 启动 Stage 4 并跑通演示 | WorkflowGraph, WorkflowExecutor, WorkflowRegistry |
| 线性工作流（输入->Agent->输出） | Node, Edge, State |
| 路由工作流（意图识别->不同Agent） | ConditionalEdge, RouterNode |
| AgentNode 与配置配合 | 包装复用，动态配置兼容 |
| 自定义 Node 和工作流 | FunctionNode, Builder 模式, lambda 语法 |
| 两个 Agent 串联协作 | 多 Agent 协作，自定义 inputKey/outputKey |
| 人工中断与断点续跑 | InterruptNode, Checkpoint, 人机协作 |
| Supervisor 多 Agent 任务分发 | SupervisorNode, SubTaskExecutorNode, 任务拆分与合并 |
