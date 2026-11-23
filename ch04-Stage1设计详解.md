# 第4章 迭代一设计详解 —— Agent 核心引擎

迭代一的目标：**用最少的代码证明 Agent 的核心闭环能跑通**。没有工厂、没有配置中心、没有网关——一个固定场景的 Agent，通过 CLI 和用户对话。

但"简单"不等于"简陋"。迭代一已经包含了 Agent 的全部核心能力：LLM 对话、记忆管理、工具调用、RAG 知识检索、MCP 扩展、Skill 增强。这些组件在迭代二和迭代三中原封不动复用。

---

## 4.1 设计目标

| 目标 | 实现方式 |
| --- | --- |
| 证明 Agent 能跑通 | 一个完整场景的 Agent，端到端闭环 |
| 代码尽可能少 | 固定场景，硬编码组装 |
| 体现所有核心能力 | LLM + Memory + Tool + RAG + MCP + Skill |
| 组件可复用 | 所有核心组件以接口形式设计，迭代二/三直接复用 |

---

## 4.2 启动与初始化

```Plaintext
Stage1App.main()
    │
    ├── 1. 加载配置（stage1.yml）
    │      └── LLM 配置、模型名称、知识库路径、MCP Server 命令...
    │
    ├── 2. 创建基础设施
    │      ├── LLMGateway（LLM 客户端，OpenAI 兼容协议）
    │      └── ConversationMemory（对话记忆）
    │
    ├── 3. 创建可选组件（按配置决定是否启用）
    │      ├── RagService（RAG 检索服务）
    │      ├── McpClient（MCP 客户端，连接外部工具）
    │      └── SkillManager（技能管理器，从文件系统加载）
    │
    ├── 4. 组装 Agent（Builder 模式）
    │      └── BaseAgent.builder()
    │              .id("agent_001")
    │              .model(llm)
    │              .systemPrompt("...")
    │              .toolManager(toolManager)
    │              .memory(memory)
    │              .ragService(ragService)    // 可选
    │              .skillManager(skillManager) // 可选
    │              .build()
    │
    └── 5. 启动 CLI 循环
           └── while(true): 读取输入 → agent.chat() → 输出响应
```

> 迭代一的"硬编码"体现在步骤 4——Agent 的 ID、Prompt、工具列表都是写死的。迭代二会用 AgentFactory + 配置文件替代这个过程。

---

## 4.3 BaseAgent 与 ReACT 循环

BaseAgent 是整个平台最核心的类。三个迭代都用同一个 BaseAgent，代码完全不变。

### BaseAgent 的组成

```Plaintext
BaseAgent
├── id: String                       ← Agent 唯一标识
├── name: String                     ← 显示名称
├── model: ChatLanguageModel         ← LLM 客户端
├── systemPrompt: String             ← 系统提示词（角色设定）
├── toolManager: ToolManager         ← 工具注册表
├── toolSpecifications: List         ← 工具规格（声明给 LLM 的描述）
├── knowledgeBase: String            ← 知识库名称
├── ragService: RagService           ← RAG 检索服务（可选）
├── memory: ConversationMemory       ← 对话记忆
└── skillManager: SkillManager       ← 技能管理器（可选）
```

### ReACT 循环详解

`chatWithTools()` 是 ReACT 循环的核心实现：

```Plaintext
function chatWithTools(chatHistory, sessionId):
    maxIterations = 10
    currentHistory = copy(chatHistory)

    for i = 1 to maxIterations:
        // 调用 LLM（带工具描述）
        response = model.chat(currentHistory, toolSpecifications)

        if response 包含 Function Calling 请求:
            // 把 LLM 的工具调用请求加入历史
            currentHistory.add(response)

            // 执行所有工具调用
            for each toolRequest in response.toolRequests:
                try:
                    result = toolManager.execute(toolRequest.name, toolRequest.args)
                    memory.recordToolCall(sessionId, toolRequest.name, args, result)
                catch (ToolExecutionException e):
                    result = ToolResult.error("工具执行失败: " + e.message)
                currentHistory.add(toolRequest, result)

            // 继续循环，让 LLM 看到工具结果
            continue

        else:
            // LLM 直接给出文本响应，循环结束
            return response.text

    // 超出循环上限
    return "处理超时，请简化您的问题后重试"
```

**关键设计点**：

| 设计点 | 方案 | 理由 |
| --- | --- | --- |
| LLM 自主决策 | 把工具描述交给 LLM，由 LLM 判断是否需要调用 | 代码中不硬编码"什么时候调工具"，由 LLM 推理决定 |
| 多轮工具调用 | 循环上限 10 次，每轮可调用多个工具 | 支持复杂的多步推理（先查日期→再查数据→再分析） |
| 工具结果回传 | 结果作为新消息加入历史，LLM 可看到所有返回值 | LLM 基于完整上下文决定下一步 |
| 工具异常容错 | 工具执行失败时返回错误信息而非抛出异常 | LLM 可以根据错误信息调整策略（换工具/换参数） |

### ReACT 交互示例

```Plaintext
用户：帮我分析一下最近的市场趋势

[第1轮] LLM 输出 Function Calling:
  → datetime()                           获取当前日期

[工具返回] 2024-12-15

[第2轮] LLM 输出 Function Calling:
  → market_overview()                    查看大盘

[工具返回] 上证指数 3350，涨幅 0.8%

[第3轮] LLM 输出 Function Calling:
  → sector_analysis("technology")        查看板块

[工具返回] 科技板块涨幅 1.2%，半导体领涨

[第4轮] LLM 输出文本：
  "根据今日数据，整体市场呈上涨趋势。上证指数收于3350点，涨幅0.8%。
   科技板块表现突出，涨幅1.2%..."
```

---

## 4.4 RAG 管道

### 离线阶段：知识入库

```Plaintext
knowledge/                              文档目录
├── doc_a.md
├── doc_b.md
└── doc_c.md
        │
        ▼
  DocumentSplitter                      文档切分
  （每段500字，相邻片段重叠50字）
        │
        ▼
  EmbeddingService                      文本 → 向量
  （768 维向量）
        │
        ▼
  VectorStore                           存入向量数据库
  （Milvus Collection 或内存）
```

> **迭代二扩展**：RAG 知识库加载可通过 etcd 配置推送动态触发。VectorStore 接口新增了按知识库隔离搜索和按知识库删除的能力。

### 在线阶段：检索增强

```Plaintext
用户提问
    │
    ▼
  EmbeddingService                      问题 → 向量（同一模型，保证语义空间一致）
    │
    ▼
  VectorStore.search(query, topK=5)     向量相似度搜索
    │
    ▼
  过滤：score >= 阈值                    相关度过滤
    │
    ▼
  拼接到 Prompt 的 RAG Context 区域
```

**关键约束**：Embedding 模型必须和知识入库时用同一个。入库用 `text-embedding-3-large`，查询时也必须用 `text-embedding-3-large`，否则语义空间不一致，检索结果不准确。

---

## 4.5 Tool 与 MCP

### Tool 的注册与执行

```Plaintext
ToolManager（工具注册表）
├── registerTool(calculator)     ← 内置工具
├── registerTool(datetime)       ← 内置工具
├── registerTool(mcp_tool_1)     ← MCP 工具
├── registerTool(mcp_tool_2)     ← MCP 工具
│
├── execute("calculator", {expression: "25*37"})
│   └── 内置工具 → 直接调用 → 返回 ToolResult
│
└── execute("query_data", {type: "market"})
    └── MCP 工具 → JSON-RPC 到 MCP Server → 返回 ToolResult
```

对 BaseAgent 来说，内置工具和 MCP 工具都是统一的 Tool 接口。这是 MCP 协议的核心价值：**工具来源对 Agent 透明**。

### MCP Client 工作方式

```Plaintext
1. 连接（Connect）
   Agent → MCP Server: initialize
   MCP Server → Agent: 协议版本 + 能力声明

2. 发现工具（Tools/List）
   Agent → MCP Server: tools/list
   MCP Server → Agent: 工具列表（名称、描述、参数 Schema）

3. 调用工具（Tools/Call）
   3a. 健康检查：transport.isConnected() / client.IsAlive()
       ├── 存活 → 直接调用
       └── 断连 → 自动重连（reconnect），重连失败才降级
   3b. Agent → MCP Server: tools/call {name, args}
       MCP Server → Agent: 执行结果

4. 断开（Disconnect）
   关闭连接，释放资源
```

| 传输方式 | 原理 | 适用场景 |
| --- | --- | --- |
| **stdio** | 启动子进程，通过标准输入输出通信 | 本地开发、工具和平台同机部署 |
| **HTTP SSE** | 通过 HTTP 长连接通信 | 远程部署、工具作为独立微服务 |

### 执行路径

```Plaintext
ReACT 循环中 LLM 发起 Function Calling
    │
    ▼
toolManager.execute(toolName, params)
    │
    ├── 内置 Tool → 直接调用 tool.execute(params)
    │                  → 返回 ToolResult
    │
    └── MCP Tool  → 健康检查 isConnected() / IsAlive()
                       ├── 存活 → callTool(name, params)
                       │          → JSON-RPC 到 MCP Server → 返回结果
                       └── 断连 → 自动 reconnect()
                                  ├── 重连成功 → callTool(name, params)
                                  └── 重连失败 → 返回 ToolResult.error()
```

---

## 4.6 Skill 增强机制

Skill 是一个声明式的能力包，由一个 `SKILL.md` 文件定义：

```Plaintext
skills/xxx/SKILL.md
├── YAML 元信息
│   ├── name: 技能名称
│   ├── description: 技能描述
│   ├── tools: [tool_1, tool_2]        ← 依赖的工具列表
│   └── examples: ["触发示例1", "触发示例2"]
│
└── Markdown 正文（专业 System Prompt）
    "你是 XXX 领域的专家。根据以下信息..."
```

### Skill 执行流程

```Plaintext
用户输入
    │
    ▼
SkillManager.matchAndExecute()
    │
    ├── 1. 匹配 Skill
    │      策略：LLM 路由 / 关键词 / 混合
    │
    ├── 2. 注入工具
    │      Skill 声明了依赖的工具 → 检查 Agent 是否已有 → 没有则动态注册
    │
    ├── 3. 替换 Prompt
    │      用 Skill 的专业 Prompt 替换默认 System Prompt
    │
    └── 4. 执行增强对话
           agent.chatWithSkill(userMessage, sessionId, skillPrompt)
```

### Skill 的核心价值

没有 Skill 时，Agent 用通用 Prompt，知道有工具但不会主动规划工具调用顺序。有了 Skill 的专业 Prompt，Agent 变成特定领域的专家，会更有条理地编排多步工具调用。

```Plaintext
无 Skill：用户问复杂问题 → Agent 泛泛而谈，可能不调工具
有 Skill：用户问复杂问题 → Agent 按专业流程调用多个工具 → 结构化回答
```

> **迭代二扩展**：迭代二中 Skill 可通过 etcd JSON 推送动态注册（无需 SKILL.md 文件），支持运行时增删。

---

## 4.7 错误处理与降级

Agent 在运行过程中会遇到各种异常，需要优雅处理而非直接崩溃：

| 异常场景 | 处理策略 | 用户感知 |
| --- | --- | --- |
| **LLM API 超时** | 重试 2 次（指数退避），仍失败则返回"服务繁忙" | 收到提示而非无响应 |
| **LLM 返回格式异常** | 解析失败时当作纯文本处理 | 仍能收到回复，可能非最优 |
| **工具执行失败** | 将错误信息回传给 LLM，让 LLM 换策略 | LLM 可能换工具或换参数 |
| **MCP Server 断连** | 先尝试自动重连，重连失败则返回错误信息让 LLM 换策略 | LLM 可能换工具或告知用户服务暂不可用 |
| **RAG 检索无结果** | 跳过 RAG 增强，走普通对话路径 | 回复不基于知识库，但不会中断 |
| **ReACT 循环超限** | 返回"请简化问题"提示 | 明确引导用户 |
| **Token 超限** | 截断最早的历史消息，保留最近上下文 | 丢失早期上下文，但当前对话正常 |

> **设计原则**：每个可选组件（RAG、Skill、MCP）的失败都不应该导致整个请求失败。Agent 应该在降级模式下继续工作，只是回答质量可能下降。

---

## 4.8 可观测性埋点

虽然是 MVP 阶段，但核心组件已预留了可观测性接口：

```Plaintext
┌──────────────────────────────────────────────────────┐
│                 可观测性埋点                           │
│                                                      │
│  BaseAgent                                           │
│  ├── 请求开始日志：sessionId, userId, agentId         │
│  ├── LLM 调用日志：模型名, prompt tokens, completion tokens │
│  ├── 工具调用日志：工具名, 参数, 耗时, 结果状态       │
│  ├── RAG 检索日志：查询, 命中文档数, 最高相似度       │
│  ├── Skill 匹配日志：匹配结果, 匹配策略, 耗时        │
│  └── 请求完成日志：总耗时, 循环次数, 最终状态         │
│                                                      │
│  指标采集（为 Prometheus 预留）                       │
│  ├── agent_request_total            请求计数          │
│  ├── agent_request_duration_seconds 请求延迟          │
│  ├── agent_llm_tokens_total         Token 消耗       │
│  ├── agent_tool_calls_total         工具调用次数      │
│  └── agent_errors_total             错误计数          │
└──────────────────────────────────────────────────────┘
```

> 迭代一会以结构化日志形式输出。迭代三接入 Prometheus + Grafana 后，这些指标会自动汇聚到监控面板。

---

## 小结

迭代一虽然只有约 500 行代码，但已经包含了 Agent 的全部核心能力：

1. **BaseAgent + ReACT 循环**：LLM 自主决策工具调用，支持多轮推理，循环上限保护

1. **RAG 管道**：离线切分入库 → 在线检索增强，可选组件，失败时自动降级

1. **Tool + MCP**：统一接口，内置工具和 MCP 工具对 Agent 透明

1. **Skill 增强**：专业 Prompt + 工具注入，让 Agent 在特定领域表现更好

1. **错误处理与降级**：每个组件的失败独立处理，不连锁崩溃

1. **可观测性**：预留日志和指标接口，为生产监控做准备

迭代一的局限：一切硬编码。要加新场景就得改代码。这就是迭代二要解决的问题。
