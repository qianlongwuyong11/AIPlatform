前面三个 Stage，我们搭建了一个功能完备的 Agent 平台：Agent 能对话、能调用工具、能检索知识库、能动态配置。但有一个根本限制——**每次只有一个 Agent 在干活**。

Stage 4 要解决的问题就是：**让多个 Agent 像团队一样协作，完成单个 Agent 搞不定的复杂任务**。

---

## 1. 为什么需要工作流

### 1.1 Stage 3 的能力边界

Stage 3 已经很强了：一个 Agent 能用 ReAct 循环多步推理，能调工具，能查知识库。但在以下场景中，单 Agent 会力不从心：

**场景一：多角度分析**

> 用户："孩子沉迷手机怎么办，从教育和技术两个角度给我建议"

单个育儿顾问只能给一个角度的回答。你需要两个不同专业背景的 Agent 各自从自己的角度分析，然后把结果合并。

**场景二：初稿 + 润色的流水线**

> 用户："帮我写一份育儿方案"

理想流程：育儿顾问先出初稿 → 内容编辑审阅润色 → 输出终稿。两个 Agent 串联，前一个的输出是后一个的输入。

**场景三：意图路由**

> 用户有时问育儿，有时问技术

需要一个"前台"先判断用户意图，再分配给对应的专业 Agent。

**场景四：需要人工确认**

> Agent 生成了方案，但你想补充一些要求后再让它继续完善

需要工作流能暂停，等你确认后再继续。

这些场景有一个共同点：**都不是一个 Agent 能独立完成的，需要"编排"**。

### 1.2 一句话概括 Stage 4

> Stage 4 = 给 Agent 团队配了一个"项目经理"，负责分配任务、控制流程、保存进度。

|  | Stage 1-3 | Stage 4 |
| --- | --- | --- |
| **架构** | 用户 → 单个 Agent → 回答 | 用户 → 工作流引擎 → Agent A → Agent B → 合并结果 |
| **特点** | 一个 Agent 干所有事 | 流程编排，统一调度、状态传递、断点保存 |

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

---

## 2. 核心设计：四个概念搞定一切

整个工作流引擎只有四个核心抽象，理解了这四个，整个 Stage 4 就通了：

| 概念 | 角色 | 类比 |
| --- | --- | --- |
| **WorkflowState** | 所有节点共享的数据 | 白板——谁都能读写 |
| **WorkflowNode** | 执行一步操作 | 工位——从白板读，处理后写回白板 |
| **WorkflowEdge** | 决定下一步走哪 | 指示牌——固定方向或看条件选路 |
| **WorkflowGraph** | 把上面三者组装成图 | 乐高——用 Builder 拼起来 |

### 2.1 WorkflowState —— 共享黑板

所有节点面对同一块白板。节点 A 写上去的内容，节点 B 可以直接读到：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| 操作者 | 动作 | 白板上的内容 |
| --- | --- | --- |
| 用户 | 写入 user_input | `user_input: "孩子挑食怎么办"` |
| Agent A | 写入 draft | `draft: "作为育儿顾问建议..."` |
| Agent B | 读取 draft，写入 agent_response | `agent_response: "优化后的建议..."` |

节点之间不直接通信，全部通过 State 传数据。这样做的好处：

- **解耦**——换掉 Agent A 不影响 Agent B

- **可观测**——调试时打印 State 就能看到全貌

- **可持久化**——把 State 存下来就能断点续跑

```Java
// 写
state.set("user_input", "孩子挑食怎么办");

// 读
String input = state.get("user_input");
```

### 2.2 WorkflowNode —— 执行步骤

每个节点做一件事：**从 State 读输入 → 处理 → 把结果写回 State**。

**Node 不等于 Agent**。Node 是通用的执行单元，Agent 只是其中一种：

| Node 类型 | 做什么 | 类比 |
| --- | --- | --- |
| AgentNode | 调用一个 Agent | 请一个专家来处理 |
| FunctionNode | 执行一段代码 | 机器自动加工 |
| RouterNode | 判断走哪条路 | 前台分诊 |
| InterruptNode | 暂停等人确认 | 等领导签字 |
| SupervisorNode | 把任务拆成子任务 | 项目经理分活 |
| SubTaskExecutorNode | 并行执行子任务 | 工人们同时干活 |

所有 Node 都实现同一个接口：

```Java
public interface WorkflowNode {
    String getId();
    WorkflowState execute(WorkflowState state);
}
```

就是一个函数：数据进来，处理，数据出去。

### 2.3 WorkflowEdge —— 流转规则

边决定"做完这一步，下一步走哪"。

**普通边（SimpleEdge）**—— 固定方向，永远走这条路：

| 流程 | 方向 |
| --- | --- |
| 预处理 → Agent | 固定 |
| Agent → 后处理 | 固定 |

**条件边（ConditionalEdge）**—— 看着 State 上的标记决定走哪条：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| State 中 intent 的值 | 走向 |
| --- | --- |
| "tech" | → 技术 Agent |
| "parenting" | → 育儿 Agent |
| 其他 | → 默认 Agent |

条件边就是一个函数：读 State → 返回下一个节点的 ID。

```Java
graph.addConditionalEdge("分类器", state -> {
    String intent = state.get("intent");
    switch (intent) {
        case "tech":      return "tech_agent";
        case "parenting": return "parenting_agent";
        default:          return "default_agent";
    }
});
```

### 2.4 WorkflowGraph —— 组装整张图

用 Builder 模式把 Node 和 Edge 拼在一起，就是一张完整的工作流图。

**最简单的线性工作流：**

```Java
WorkflowGraph graph = WorkflowGraph.builder()
    .addNode("agent", new AgentNode("agent", registry, "parenting_advisor"))
    .addEdge(START, "agent")
    .addEdge("agent", END)
    .build();
```

流程：`START → [育儿顾问] → END`

**稍微复杂的路由工作流：**

```Java
graph = WorkflowGraph.builder()
    .addNode("router", new RouterNode(...))
    .addNode("tech_agent", new AgentNode(..., "tech_helper"))
    .addNode("parenting_agent", new AgentNode(..., "parenting_advisor"))
    .addEdge(START, "router")
    .addConditionalEdge("router", state -> state.get("routed_agent"))
    .addEdge("tech_agent", END)
    .addEdge("parenting_agent", END)
    .build();
```

流程：

| 路径 | 走法 |
| --- | --- |
| 技术问题 | START → [路由] → [技术助手] → END |
| 育儿问题 | START → [路由] → [育儿顾问] → END |

**Builder 的好处**：你可以清楚地看到图的完整结构——有哪些节点、怎么连接、哪里有条件分支。一眼就能看懂工作流的全貌。

---

## 3. 执行器：引擎怎么跑

图定义好了，谁来跑？`WorkflowExecutor`。

### 3.1 执行流程

执行器的工作很简单——从 START 出发，一个节点一个节点地执行，直到 END：

**单步执行循环：**

| 步骤 | 动作 |
| --- | --- |
| ① | 从 State 读取当前节点需要的输入 |
| ② | 执行节点（调用 Agent / 运行代码 / 做路由判断） |
| ③ | 把结果写回 State |
| ④ | 如果配了 Redis，保存 Checkpoint |
| ⑤ | 根据边找下一个节点，重复 ① |

```Java
// 伪代码
currentNode = 找到第一个节点;
while (currentNode != END) {
    state = currentNode.execute(state);   // 执行节点
    saveCheckpoint(state);                // 存快照
    currentNode = 找下一个节点(state);     // 根据边决定下一步
}
```

### 3.2 中断与恢复

如果某个节点是 InterruptNode（人工确认节点），执行器会暂停：

**正常执行：**

| 步骤 | 状态 |
| --- | --- |
| 执行 [Agent 出初稿] | 状态: running |
| 执行 [InterruptNode] | 检测到中断标记，状态变为: paused |
| 保存 Checkpoint | 返回给用户 |

**恢复执行：**

| 步骤 | 状态 |
| --- | --- |
| 用户输入补充意见 | — |
| 从 Checkpoint 恢复 State | 清除中断标记 |
| 执行 [Agent 润色] | 状态: running |
| 到达 END | 状态: completed |

这就是"断点续跑"——工作流暂停后，从暂停的节点后面继续执行，不需要从头开始。

---

## 4. 多 Agent 协作模式

Stage 4 内置了四种常见的协作模式，每种解决一类问题：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

### 4.1 Pipeline（流水线）

**场景**：任务有明确的阶段，前一个的输出是后一个的输入。

**流程**：`用户输入 → [预处理] → [Agent 处理] → [后处理] → 输出`

**例子（pipeline-demo）**：

| 步骤 | 节点 | State 变化 |
| --- | --- | --- |
| 用户输入 | — | `user_input: "孩子挑食"` |
| 预处理 | FunctionNode | `user_input: "[工作流预处理] 孩子挑食"` |
| Agent 处理 | 育儿顾问 | `agent_response: "作为育儿顾问..."` |
| 后处理 | FunctionNode | `agent_response: "📝 作为育儿顾问...\n--- 由工作流引擎生成 ---"` |

### 4.2 Router（路由）

**场景**：不同类型的问题由不同专业的 Agent 处理。

**流程**：`用户输入 → [分类器] → 条件边选择 → [对应 Agent] → 输出`

**例子（classifier-demo）**：

| 用户输入 | 分类结果 | 走向 |
| --- | --- | --- |
| "我的程序有 bug" | intent = "tech" | → 技术助手 |
| "孩子不爱吃饭" | intent = "parenting" | → 育儿顾问 |
| "今天天气怎样" | intent = "default" | → 默认 Agent |

### 4.3 Collab（串联协作）

**场景**：两个 Agent 串联，第一个出初稿，第二个润色。

**流程**：`用户输入 → [育儿顾问: 出初稿] → [内容编辑: 润色] → 输出`

**关键点**：自定义 `inputKey` / `outputKey`：

| Agent | 从哪读 | 写到哪 |
| --- | --- | --- |
| 育儿顾问 | `user_input` | `draft`（初稿） |
| 内容编辑 | `draft`（读初稿） | `agent_response`（终稿） |

**State 变化过程**：

| 步骤 | State 内容 |
| --- | --- |
| 用户输入 | `user_input = "孩子挑食怎么办"` |
| 育儿顾问执行后 | `draft = "建议从以下方面..."` |
| 内容编辑执行后 | `agent_response = "优化后的建议..."` |

两个 Agent 通过 State 传递数据，各司其职。

### 4.4 Supervisor（主管模式）

**场景**：复杂任务需要多个 Agent 从不同角度**同时**处理，最后合并结果。

**流程**：`用户输入 → [Supervisor: 拆任务] → [SubTaskExecutor: 并行执行] → 合并输出`

**例子（supervisor-demo）**：

用户输入："孩子沉迷手机怎么办"

Supervisor 拆成两个子任务：

| 子任务 | 分配给 | 内容 |
| --- | --- | --- |
| #1 | 育儿顾问 | "请从育儿角度分析：孩子沉迷手机怎么办" |
| #2 | 技术助手 | "请从技术角度给出解决方案：孩子沉迷手机怎么办" |

SubTaskExecutorNode **并行**调用多个 Agent：Go 使用 goroutine，Java 使用虚拟线程，Python 使用 `asyncio.gather` 与 Semaphore 控制并发，最后按任务顺序合并结果。

**Supervisor vs Router 的区别**：

|  | Router | Supervisor |
| --- | --- | --- |
| 选择方式 | N 选 1 | N 全选 |
| 执行方式 | 串行 | 并行 |
| 输出 | 单个 Agent 的回答 | 多个 Agent 的回答合并 |

---

## 5. 高级能力

### 5.1 断点续跑（Checkpoint）

每个节点执行完后，自动保存当前状态快照：

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| executionId | 执行唯一 ID | "a3f2b1" |
| currentNodeId | 当前执行到的节点 | "agent" |
| stateData | State 完整数据 | {user_input: "...", agent_response: "..."} |
| status | 当前状态 | running / paused / completed / failed |

**存储方案**：

| 方案 | 特点 | 什么时候用 |
| --- | --- | --- |
| Redis | 持久化，跨重启 | 默认推荐 |
| 内存 | 不跨重启 | Redis 没装时自动降级 |

Redis 没连上时自动降级为内存存储，工作流照样能跑——只是重启后断点会丢失。

### 5.2 人在回路（InterruptNode）

工作流可以在任意节点暂停，等人工确认后继续：

**完整交互流程：**

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| # | 步骤 | 说明 |
| --- | --- | --- |
| 1 | Agent 出初稿 | 正常执行 |
| 2 | InterruptNode 暂停 | 在 State 上设 `_interrupted=true`，保存 Checkpoint |
| 3 | CLI 提示用户 | 显示初稿内容，等待用户输入 |
| 4 | 用户输入补充意见 | 可以直接回车确认继续 |
| 5 | Resume 恢复执行 | 清除中断标记，从下一个节点继续 |
| 6 | Agent 润色 | 基于用户意见优化初稿 |
| 7 | 完成 | 输出终稿 |

InterruptNode 实现非常简单——只需要两个标记：

| State Key | 值 | 作用 |
| --- | --- | --- |
| `_interrupted` | true | 告诉执行器：暂停 |
| `_interrupt_prompt` | 初稿内容 | 告诉 CLI：显示这个提示 |

执行器看到 `_interrupted=true`，停止执行，保存 paused 状态的 Checkpoint。CLI 检测到中断，提示用户输入，然后调用 `resume()` 恢复。

### 5.3 Channel 机制

State 不是简单的 Map。每个 key 可以关联一个 Channel，控制"多个节点写同一个 key 时怎么合并"：

| Channel 类型 | 行为 | 用在哪 |
| --- | --- | --- |
| LastValue | 新值覆盖旧值 | 默认行为，"当前回答"只要最新的 |
| Append | 追加到列表 | "消息历史"，每条都保留 |
| Ephemeral | 本轮有效，下一步清空 | "中间计算结果"，用完即丢 |

当前 Stage 4 默认全部用 LastValue，Channel 机制是为后续扩展预留的。

---

## 6. 与 Stage 1-3 的关系

### 6.1 零侵入设计

Stage 4 是纯新增模块，**不改 Stage 1-3 的任何代码**：

|  | Stage 1-3（不动） | Stage 4（新增） |
| --- | --- | --- |
| 核心模块 | agent/、config/、llm/、tool/、rag/、memory/、gateway/ | workflow/core/、workflow/patterns/、workflow/integration/、workflow/checkpoint/ |

### 6.2 包装而非重写

现有的 Agent 不是重写，而是通过 integration 包包装成 Node：

| 你之前写的代码 | 在工作流中的角色 |
| --- | --- |
| BaseAgent（ReAct 循环） | AgentNode 内部的执行逻辑 |
| AgentRegistry | AgentNode 通过 Registry 获取 Agent |
| Tool（工具） | ToolNode |
| etcd 动态配置 | AgentNode 执行时拿最新的 prompt/tools |
| Redis | Checkpoint 持久化 |

**关键设计**：工作流的**图结构**（哪些节点、怎么连）是代码定义的，稳定不变。但每个节点内部的 **Agent 行为**（prompt、tools、model）仍然由 YAML/etcd 动态控制——改了配置，下次执行自动生效。

| 层面 | 谁控制 | 是否可变 |
| --- | --- | --- |
| 图结构（节点、边） | 代码定义 | 稳定不变 |
| Agent 行为（prompt、tools） | YAML / etcd 配置 | 动态可变 |

---

## 7. 设计灵感与选型

### 7.1 为什么不直接用 LangGraph

LangGraph（LangChain 团队的工作流框架）是目前最流行的 Agent 工作流方案，我们的设计灵感确实来自它。

| 选项 | 问题 |
| --- | --- |
| 直接用 LangGraph | 通用框架的抽象、状态语义和升级路线不完全受业务控制，深度定制时容易受框架边界约束 |
| 用 Temporal / Camunda | 太重，概念多，为通用工作流设计而非 Agent 特化 |
| **自己实现**（我们的选择） | 轻量透明，专为 Agent 场景设计，掌握底层原理更能带领业务顺应后续AI的变化冲击。而不是依赖某种不成熟框架被框架的更新迭代牵着走。 |

选择自实现的原因：

- **Agent 特化**：专为 Agent 场景设计，不背通用引擎的包袱

- **面试加分**：能说清"我自己设计了一个工作流引擎"，比"我用了 Temporal"更有说服力

### 7.2 从 LangGraph 学到了什么

| LangGraph 的创新 | 我们的实现 | 价值 |
| --- | --- | --- |
| 图 = 状态机 | WorkflowGraph + Node + Edge | 清晰的流程建模 |
| Channel 写入规则 | LastValue / Append / Ephemeral | 状态合并的语义控制 |
| 内置 Checkpoint | CheckpointStore + Redis/Memory | 断点续跑 + 人工确认 |

### 7.3 设计决策总结

| 决策 | 理由 |
| --- | --- |
| 串行执行为主，并行在节点内部 | 降低复杂度，并行逻辑封装在 SubTaskExecutorNode 里 |
| 图结构代码定义 | 学员先理解概念，比 YAML 配置更直观 |
| Redis Checkpoint + 内存降级 | 推荐用 Redis，没装也能跑 |
| Stage 4 独立模块 | 零侵入 Stage 1-3，学员可选学 |
| CLI 模式 | 学习友好，直接终端交互 |

---

## 8. 八个 Demo 一览

Stage 4 注册了 8 个演示工作流，覆盖了从简单到复杂的所有模式：

| # | 名称 | 模式 | 流程 |
| --- | --- | --- | --- |
| 1 | linear-demo | 线性 | 输入 → Agent → 输出 |
| 2 | router-demo | 路由 | 输入 → Router → N 个 Agent（选一个） |
| 3 | tech-demo | 线性 | 输入 → 技术助手 → 输出 |
| 4 | pipeline-demo | 流水线 | 预处理 → Agent → 后处理 |
| 5 | classifier-demo | 条件路由 | 分类器 → 条件边 → N 个 Agent |
| 6 | collab-demo | 串联协作 | 育儿顾问（初稿）→ 内容编辑（润色） |
| 7 | interrupt-demo | 人工确认 | Agent（初稿）→ 暂停 → Agent（润色） |
| 8 | supervisor-demo | 主管 | Supervisor 拆任务 → 并行执行 → 合并 |

**建议学习顺序**：1 → 2 → 3→ 4 → 5 → 6 → 7 → 8 

下一步请选择 Java、Go 或 Python 实战，逐个跑通 8 个 Demo。Python 入口：[Stage4：Python实战](https://ecnz1eq1tqym.feishu.cn/wiki/RaDmwkIhLi9CLVkdS1GcsoqTn1f)。
