# Stage2 学习验收通关书

本书对应 [ch05-Stage2设计详解.md](ch05-Stage2设计详解.md)，以当前 Java 实现为核对依据。目标是能够说明配置如何变成可用 Agent，解释 MCP 客户端的隔离目的与边界，并沿真实控制流判断健康警告、配置故障和热更新如何影响服务。

创建日期：2026-11-18。全部任务均为开卷静态阅读或明确标注的设计理解题；本次未运行应用、etcd、MCP 或模型请求，也未验证真实告警投递。文档中的架构示意、生产建议与当前源码可能不同，回答时请区分设计意图、代码事实和拟新增方案。

路线：配置装配 → MCP 内联与连接隔离 → 健康检查与故障告警 → 动态配置 → 生命周期与 CLI。使用 T 编号，避免与 Stage1 的 S 编号混淆。你关注的两个重点集中在 T2.1—T2.3 和 T3.1—T3.4。可以优先完成这两组，再继续后续主流程和异常题。

源码定位（以下路径均相对于 bitguide-agent-platform-main）：

- [Stage2App](bitguide-agent-platform-main/src/stage2/java/com/bitguide/stage2/Stage2App.java)：启动、依赖注入、CLI 与退出清理。
- [AgentConfig](bitguide-agent-platform-main/src/main/java/com/bitguide/config/AgentConfig.java)、[AgentFactory](bitguide-agent-platform-main/src/main/java/com/bitguide/agent/AgentFactory.java)：配置、装配、MCP 与健康检查。
- [McpClient](bitguide-agent-platform-main/src/main/java/com/bitguide/mcp/McpClient.java)：连接、工具发现和客户端状态。
- [AgentRegistry](bitguide-agent-platform-main/src/main/java/com/bitguide/agent/AgentRegistry.java)、[AgentEntry](bitguide-agent-platform-main/src/main/java/com/bitguide/agent/AgentEntry.java)：注册、查询、替换与引用计数。
- [EtcdConfigWatcher](bitguide-agent-platform-main/src/main/java/com/bitguide/config/dynamic/EtcdConfigWatcher.java)、[ConfigEvent](bitguide-agent-platform-main/src/main/java/com/bitguide/config/dynamic/ConfigEvent.java)、[DynamicConfigManager](bitguide-agent-platform-main/src/main/java/com/bitguide/config/dynamic/DynamicConfigManager.java)：事件产生、解析和处理。
- [logback.xml](bitguide-agent-platform-main/src/main/resources/logback.xml)、[prometheus.yml](bitguide-agent-platform-main/docker/prometheus.yml)：核对已有日志输出与监控采集配置的证据范围。

每题只需提交其“要求与提交物”指定的一段回答，不需要截图、画图、写代码或搭建服务。每题最多两轮：首次通过即可结束；第二轮后模型直接补全，不追加第三轮。作答时使用“回答 T3.1”等编号，原文与反馈将保留在对应题目下。当前不提供参考答案，不将阅读完成等同于通过。

---

### T1.1 从 Agent 配置到注册表

- **目标与动作**：阅读章节 §5.1—§5.3，并沿 AgentRegistry.register → AgentFactory.createAgent 查看组装过程。本题假设首次注册 id 为 translator 的 Agent，modelRef 指向已有静态模型配置，toolRefs 只包含 datetime，不声明 MCP、Skill 或知识库，各依赖调用正常。关注配置、工厂返回对象和注册表之间的职责。
- **要求与提交物**：用一小段话说明这份配置如何成为可按 ID 查询的 Agent。
- **通过标准**：回答需要连接模型选择、Prompt 与工具装配、BaseAgent 构建和注册表保存的关键步骤，并区分工厂创建与注册表登记；不要求复述 Stage1 的模型对话循环。

#### T1.1 首次作答记录

2026-11-18 · 第一轮

**用户原文**

> 进入**register方法，首先会去当前agentRegistry缓存的 private final ConcurrentHashMap\<String, AgentEntry> agents = new ConcurrentHashMap<>();这个agent实例map当中根据**_agentId会答应的agent。_
>
> 如果存在的话，需要更新当前的agentID对应的agent的配置；否则创建新的agent实例，进入createAgent方法，装配完成*LLM、Prompt、ToolManager、 Skill的那个之后，构建新的agent，并且将该agent创建的entry对象存入缓存当中。*

**模型改正建议**

你已说明查询注册表、调用工厂装配 Agent、再包装为 entry 保存的主要顺序，也区分了工厂创建与注册表保存。前面被中断的输入视为同一次作答的未完成内容，本条完整回答计为第一轮。

两处表述需要收紧：`agents` 保存的是 `AgentEntry`，其中包含 Agent 和配置，不是直接保存 Agent 实例；已存在时还要检查 `isSameConfig`，配置相同直接返回 true，只有配置不同才进入 update。不过，本题假设首次注册 translator，不必继续展开更新过程。另外，本题不声明 Skill，因此工厂不会为它启用 SkillManager，不能把 Skill 装配理解为必经的启用步骤。

目前“装配 LLM、Prompt、ToolManager”还没有说明配置如何决定这些内容。请结合 `createModel`、`getSystemPrompt` 和 `createToolManager` 补清 modelRef、systemPrompt、toolRefs 的作用，并将题设中的 datetime 与实际工具注册联系起来。最后明确 `new AgentEntry(agent, config)` 由哪一层创建，以及 `agents.put(agentId, entry)` 如何使 translator 能按 ID 被查询。

请仍用一小段话说明这份配置从首次 register 到工厂装配、再回到注册表保存的完整路径，无需分析更新分支或 Stage1 对话循环。

当前状态：第一轮需补强；主要调用顺序与职责区分已说明，配置到模型、Prompt、工具的对应关系尚需补充。本轮依据源码静态核对，未运行应用。

#### T1.1 第二次作答记录

2026-11-18 · 第二轮

**用户原文**

> modelRef会在createModel方法当中被使用，最红调用llmGateway.getOrCreateModel方法，先查缓存，缓存没有当前model对应的ChatLanguageModel的话，会创建一个。
> systemPrompt直接从congih当中获取完成之后在agent.build的时候直接传入
> 至于*装配 ToolManager，分成三部分，内置的tool和mcp携带的tool和配置文件当中的McpServerRefs存在的话，创建这个对应的tool（有的话才创建）。其中，对于配置文件当中的McpServers对应的tool的注册，这里会为每一个agent创建独立的McpClient，并且用*inlineMcpClients.put(*agentId*, agentMcpClient);存在工厂当中。这里应该是可以同一个agent链接多个mcpClient的。
> 最终是SkillManager，看配置文件当中有没有指定吧，只能够了的话就会在agent.build的时候传入AgentFactory持有的skillManager实例，也就是说所有的agent共用同一个工厂当中的skillManeger实例。

**模型补充与完善**

你已补充模型缓存复用、Prompt 从配置传入 Builder，以及工具来源和 SkillManager 的装配方式。结合第一轮，工厂创建与注册表保存的主流程已有说明；以下补齐题设中的配置对应关系，并纠正本轮扩展描述中的对象数量与共享条件。中间关于配置来源的提问属于澄清，不另计作答轮次。

| 不足 | 完善理解 |
| --- | --- |
| 已说明 modelRef 会被使用，但未明确它如何对应模型配置。 | createModel 用 AgentConfig.modelRef 查找 ModelConfig，先尝试已注入的动态配置管理器，再回退到平台总配置的 models。本题从已有静态配置取得模型定义，随后交给 getOrCreateModel。网关实际以 modelName 加 jsonMode 后缀作为缓存键，通过 computeIfAbsent 复用或创建客户端；缓存键并非直接使用 modelRef。 |
| 工具描述未落实本题 datetime 的来源，且将注册笼统称作创建。 | 本题只有 toolRefs=[datetime]，工厂新建 ToolManager，再从 builtinTools.get("datetime") 获取已有工具并注册，不走 MCP 分支。一般情况下，内联 MCP 从独立客户端发现工具；mcpServerRefs 则从全局客户端发现的工具中按 Server 名筛选，并跳过已有同名工具，不是根据引用名称直接创建远端工具实现。 |
| 将一个 Agent 配置多个 MCP Server 推断成该内联路径为它创建多个 McpClient。 | 一次 connectInlineMcpServers 把整个 servers 列表放入同一个 McpConfig，只创建一个 agentMcpClient，由它连接多个 Server。inlineMcpClients 每个 agentId 保存一个客户端引用。Agent 若还引用全局 MCP，也可能使用全局客户端发现的工具，但这与内联列表中每个 Server 创建一个 Client 不同。 |
| “所有 Agent 共用 SkillManager”缺少启用条件。 | 只有工厂 skillManager 非 null，且该 Agent 的 skillRefs 非 null、非空时，才把工厂持有的同一个实例传入 Builder。满足条件的 Agent 共享该实例；本题没有 skillRefs，传入的是 null。这里仅判断是否启用，没有按 skillRefs 为每个 Agent 创建专属 SkillManager。 |

本题完整流程是：首次 register 查询不到 translator 条目，便调用工厂；工厂按 modelRef 选择模型，从 AgentConfig 取 Prompt（为空时使用空字符串），将 datetime 注册到新建的 ToolManager，在不启用 Skill 的条件下通过 BaseAgent.Builder 构建 Agent。健康检查后工厂返回 Agent，由 AgentRegistry 创建 `new AgentEntry(agent, config)`，再执行 `agents.put(agentId, entry)`，因此可以按 translator 查询该 Agent。这里 AgentConfig 是单个 Agent 的配置对象，应与平台总配置 BitGuideConfig 区分。

本任务验收已结束：两轮结束，模型已补全，独立掌握尚未证实。无需第三次作答；本轮对 MCP 与 Skill 的补充不计为后续任务的正式作答。本次依据源码静态核对，未运行应用。

---

### T1.2 缺失模型配置会在哪里阻止注册

- **目标与动作**：阅读 AgentFactory.createModel 和 AgentRegistry.register。本题假设首次注册 Agent A，动态配置管理器未设置，静态 models 中也没有 A 的 modelRef，其他配置有效；随后独立调用 register 注册配置正常的 Agent B。
- **要求与提交物**：用一小段话解释 A 的失败从哪里产生、如何返回，以及它是否阻止随后 B 的注册。
- **通过标准**：需要依据抛出、捕获和保存条目的位置解释 A 的返回值与注册表结果，并将影响范围限定到这次注册；不能把所有组件故障都概括为只记警告。

#### T1.2 首次作答记录

2026-11-18 · 第一轮

**用户原文**

> 如果dynamicConfigManager没有配置的话，那么就会使用全局配置当中的针对当前的modelRef的配置创建一个modelConfig，然后去 llmGateway.getOrCreateModel(modelConfig);创建对应的modelclient，不会抛出错误，agentb会争产大哥比欸注册

**模型改正建议**

你正确注意到 dynamicConfigManager 未设置时会尝试从静态全局 models 配置中查找模型。不过，本题还明确假设静态 models 中也没有 A 的 modelRef，不能沿“静态配置存在”的正常路径继续分析。`config.getModels().get(modelRef)` 是查找已有 ModelConfig，不会自动创建缺失的配置；查询不到时 modelConfig 仍为 null。

请重点查看 `createModel` 在调用 `llmGateway.getOrCreateModel(modelConfig)` 之前的 `if (modelConfig == null)` 分支，再返回 `AgentRegistry.register`，对照工厂调用、`agents.put` 与 catch 的先后位置。“不会抛出错误”的判断需要据此修正，并区分工厂抛出异常与注册方法最终向调用方返回什么。

你末句似乎是在表达 B 会正常注册；按题设，B 是随后独立发起且配置正常的另一次注册，这个方向正确，但还需说明 A 的失败为何不会阻断它，不将 A 的失败与 B 的执行合并成同一条未捕获异常路径。

请仍用一小段话回答：当动态和静态来源均无法提供 A 的模型配置时，异常从哪里产生、register 如何结束、A 是否写入注册表，以及随后独立注册 B 是否受阻。

当前状态：第一轮需补强；静态配置回退方向已找到，但遗漏静态来源也缺失的题设，尚需说明异常与注册结果。本轮依据源码静态核对，未运行应用。

#### T1.2 第二次作答记录

2026-11-18 · 第二轮

**用户原文**

> 如果静态全局配置当中也没有当前的model的配置的话，那么就会走入if (modelConfig == null) {\
> &#x20;           throw new RuntimeException("Model 配置不存在: " + modelRef);\
> &#x20;       }分支，并且在**register注册的方法啊当中catch，而你说的agentA和agentB是两次动态配置的变更，agenta的构建失败不会影响agentb的构建**

**模型补充与完善**

你已修正第一轮“不会抛出错误”的判断，正确指出 createModel 抛出异常、register 捕获异常，以及 A 的失败不会阻止随后独立注册 B。以下补齐返回值和注册表结果，并澄清题设。

| 不足 | 完善理解 |
| --- | --- |
| 已指出 register 捕获异常，但未明确它向调用方返回什么。 | AgentFactory.createModel 在 modelConfig 为 null 时抛出 RuntimeException，异常经 createAgent 传回 AgentRegistry.register；register 的 catch 记录错误日志并返回 false，不把该异常继续抛给调用方，也没有重试或恢复 A 的构建。 |
| 未明确 A 是否写入注册表及其原因。 | 首次注册时，register 先执行 agentFactory.createAgent(config)，成功后才创建 AgentEntry 并执行 agents.put。A 在模型装配阶段就抛出异常，因此后两步均未执行，注册表没有新增 A 的条目，也不会执行后续工具装配和健康检查。 |
| 把题设的两次独立注册称为“两次动态配置的变更”。 | 本题明确动态配置管理器未设置，只假设首次注册 A，随后独立调用 register 注册配置正常的 B，不涉及动态配置事件或已有 Agent 的更新。A 的异常在自己的 register 调用内被捕获并以 false 结束，因此不会阻断随后 B 的独立调用；在正常构建完成时，B 会写入注册表并返回 true。 |

本任务验收已结束：两轮结束，模型已补全，独立掌握尚未证实。异常产生、捕获位置与影响范围已说明；返回值、保存顺序和题设区别由模型补齐，无需第三次作答。本轮依据源码静态核对，未运行应用。

---

### T2.1 MCP Server 内联配置意味着什么

- **目标与动作**：重点阅读 §5.2 的内联 MCP 设计说明，对照 AgentConfig.mcpServers、mcpServerRefs、toolRefs，以及 AgentFactory.createToolManager。假设 A 在自己的 mcpServers 中提供 Server 名称、transport 和连接信息，B 仅通过 mcpServerRefs 引用全局 Server。
- **要求与提交物**：用一小段话说明内联配置与全局引用在配置归属和工具装配上的区别，并解释为何内联配置适合按 Agent 发布业务能力。
- **通过标准**：需要区分连接配置、Server 名称引用和内置工具引用，并用实际读取配置的路径解释独立发布的价值；不能把“内联”理解为将远端工具实现代码写入 Agent。

---

### T2.2 独立 MCP Client 隔离了什么，为什么需要隔离

- **目标与动作**：阅读 AgentFactory.connectInlineMcpServers、inlineMcpClients、destroyAgent，以及 McpClient 的 transports、requestIds 和 connect。假设 A、B 的 ID 不同，都只使用内联 MCP，Server 名称都为 weather，但分别连接不同 endpoint，且均创建成功；随后只销毁 A，不发生同 ID 热替换。
- **要求与提交物**：用一小段话说明两者的连接状态由哪些对象持有，为什么此设计有利于独立配置和清理，并界定隔离范围。
- **通过标准**：需要以每次创建的对象、状态表归属与 agentId 清理路径解释隔离目的，说明同名 Server 在此场景中的关系；不能将客户端连接隔离扩展为远端数据、权限或进程资源一定完全隔离。

#### T2.2 首次作答记录

**用户原文**

> （1）两个agent使用的mcpClient的连接状态由各自的inlineMcpClients.put(*agentId*, agentMcpClient);当中的agentMcpClient持有。这里的连接状态我认为包含有transport、serverInfo和CLient针对某一个单个server的requestId
>
> （2）“为什么此设计有利于独立配置和清理，并界定隔离范围”。首先，该方法确实直观上有利于独立配置和清理。至于独立配置，每个agent自己的config当中写明了mcpserver，后续的client对象由自己持有。后续的清理的时候，进入**destroyAgent方法，根据agentId获取当前的agent 持有的内联mcp 的引用，然后通过disconnectAll来释放各种资源。这样的话就能够隔离开来不同的agent 的持有的各自独立的mcpServer**

**模型改正建议**

你找对了三类连接状态，也说清了按 Agent ID 清理的路径。工厂只有一张 `inlineMcpClients` 表，A、B 各占一个条目。每次创建的 `McpClient` 实例内部才各有一套 `transports`、`serverInfos` 和 `requestIds`，其中 `requestIds` 按 Server 名称保存请求编号计数器。`destroyAgent(A)` 会从工厂的表中移除 A 的条目，再对取出的客户端调用 `disconnectAll()`。

**当前状态：待第二次作答。** 末句把隔离范围扩大到了 MCP Server 本身。请用题设中的两个 `weather` 补写一小段：说明同名为什么仍能对应不同 endpoint、销毁 A 后 B 的连接会怎样，以及仅凭这段客户端代码能否判断远端数据、权限和进程资源也彼此独立。

#### T2.2 第二次作答记录

**用户原文**

> （1）“同名为什么仍能对应不同 endpoint”我u也不知道
>
> （2）销毁A后B的链接仍然会存在
>
> （3）怨怒的那的服务的数据权限和进程资源有远端控制，这里只是调用

**模型补充与完善**

| 不足 | 完善理解 |
| --- | --- |
| 第（1）点还缺少从名称到连接的查找过程。 | `connectInlineMcpServers` 为 A、B 各执行一次 `new McpClient(inlineConfig)`。各自的配置决定连哪个 endpoint，`connect` 再用 `transports.put(name, transport)` 将连接存入当前实例的表。因此 A 查自己的 `weather` 得到通往 endpoint A 的连接，B 查自己的 `weather` 得到通往 endpoint B 的连接。两个键分属两张表，可以同名。 |
| 第（2）、（3）点判断正确，可以把本地清理与远端边界说得更具体。 | `destroyAgent(A)` 按 A 的 ID 取出并移除 A 的客户端，只对它调用 `disconnectAll()`，所以 B 的连接仍在。两个 endpoint 是否共享数据库、授权规则或服务进程，要看远端部署和配置。 |

本任务两轮验收已结束。已能判断清理结果和远端边界，同名映射机制由反馈补全。

---

### T2.3 一个内联 MCP Server 连接失败是否拖垮 Agent

- **目标与动作**：阅读 McpClient.connectAll、connect、getAllTools，以及 AgentFactory.connectInlineMcpServers、createAgent 和 AgentRegistry.register。假设首次创建 A，其内联列表有 S1、S2；S1 的 transport.connect 抛出 Exception，S2 正常连接且工具发现成功，模型等其他依赖正常。
- **要求与提交物**：用一小段话解释失败在哪一层被处理、能够装入哪些来源的 MCP 工具，以及注册流程如何结束。
- **通过标准**：需要依据异常捕获范围和连接状态保存时机推导工具来源与注册结果；不能只凭文档 §5.8 的故障表认定所有 MCP 启动失败都会阻止 Agent 注册。

#### T2.3 首次作答记录

**用户原文**

> 当内联表有两个server的时候，具体的处理位置是在for (McpConfig.ServerConfig serverConfig : config.getServers()) {
>
>             try {
>
>                 connect(serverConfig);
>
>             } catch (Exception *e*) {
>
>                 ConsoleLog.mcpConnectError(serverConfig.getName(), e);
>
>             }
>
>         }
>
> 也就要是说，其中某一个创建失败的话，只会去记录日志，另外一个S2正常创建，当前agent仍然会成功创建

**模型改正建议**

你找准了异常捕获的位置。每次循环只处理一个 Server，所以 S1 的连接异常被记录后，下一次循环仍会尝试 S2。在题设的其他依赖均正常时，你对 Agent 创建成功的判断也正确。

**待第二次作答。** 请用两句话补完后半段。① 对照 `connect` 中 `transport.connect()` 与 `transports.put(...)` 的顺序，说明 S1 抛错后表中有哪些 Server，以及 `getAllTools()` 最终能给 A 装入谁的内联工具。② 从 `createAgent` 返回处接着读 `AgentRegistry.register`，说明注册表发生什么变化、方法返回什么。

---

### T3.1 最佳努力：RAG 不可用为什么仍可能注册

- **目标与动作**：重点阅读 §5.5，并沿 AgentFactory.createAgent → healthCheck 返回 AgentRegistry.register。假设 Agent 配置 knowledgeBase 为 docs，工厂的 ragService 为 null，而模型、工具和 Builder 均正常。关注检查发生的位置、报告返回值是否被调用方使用，以及注册表是否根据警告拒绝登记。
- **要求与提交物**：用一小段话解释这次注册的实际处理与“最佳努力”的取舍。
- **通过标准**：需要说明检查依据、警告去向和注册控制流，并解释保留基础对话能力与缺少知识库能力之间的取舍；对“非阻塞”的解释必须区分不阻断注册与异步执行，不能把警告等同于依赖已恢复。

---

### T3.2 健康检查通过能够证明哪些事实

- **目标与动作**：阅读 AgentFactory.healthCheck 的三个检查分支。假设配置了一个内联 MCP Server、一个知识库和一个 Skill 引用；ragService 对象非 null，skillManager 为 null，且不提供任何远端服务实际可用的证据。只分析该方法，不借助真实连接实验。
- **要求与提交物**：用一小段话界定在此条件下“健康检查通过”日志能证明什么、不能证明什么。
- **通过标准**：需要对应各检查的实际条件和状态赋值，区分配置存在、对象存在、引用检查与真实调用探测；结论应考虑跳过检查的条件，不能把未产生 warning 推断成所有能力均可用。

---

### T3.3 健康警告是否已经进入故障告警系统

- **目标与动作**：阅读 §5.5、§5.9，对照 AgentFactory.AgentHealthReport、healthCheck、createAgent，以及 src/main/resources/logback.xml、docker/prometheus.yml。仍以 T3.1 的 RAG 缺失为例，追踪这条 warning 的输出位置，检查报告是否被保存或用于就绪状态，并查看现有监控配置覆盖的阶段。
- **要求与提交物**：用一小段话说明这条故障信号在已检查实现中走到哪里，以及文档所说的生产告警是否已有对应证据。
- **通过标准**：需要区分日志输出、报告对象、指标采集、故障通知和路由就绪控制；只对给定源码与配置作结论，不将未看到的外部部署能力说成已实现，也不凭文档中的 degraded 字样推定存在对应状态字段。

#### T3.3 首次作答记录

**用户原文**

> 在createAgent方法当中的**healthCheck返回的结果没有被上报给故障系统。**

**模型改正建议**

你对调用方的判断正确。`createAgent` 调用 `healthCheck(config)` 后没有接收返回的 `AgentHealthReport`，因此这条创建路径没有保存报告，也没有依据它修改 Agent 的路由就绪状态。还需要交代 warning 在方法返回前去了哪里，以及监控配置能证明什么。

**待第二次作答。** 请沿 RAG 缺失这条 warning 补写两句。先从 `healthCheck` 的 `log.warn` 追到 `logback.xml` 的 appender，说出维护者在哪里能看到它。再看 `docker/prometheus.yml` 的两个采集任务，说明它们面向哪个阶段，以及这份配置能否证明这条 warning 已转成指标并触发故障通知。结论限定在题给源码与配置内。

#### T3.3 第二次作答记录

**用户原文**

> 具体位置在 *// 输出警告日志*
>         for (String w : report.getWarnings()) {
>             log.warn("[HealthCheck] Agent [{}]: {}", *config*.getId(), w);
>         }
>         if (report.getWarnings().isEmpty()) {
>             log.info("[HealthCheck] Agent [{}] 健康检查通过", *config*.getId());
>         }
> 也就是说会记录日志

**模型补充与完善**

| 不足 | 完善理解 |
| --- | --- |
| 已定位到写日志的语句，还差日志的输出位置。 | RAG 缺失时，循环用 `log.warn` 写出 Agent ID 和警告内容。`src/main/resources/logback.xml` 的根日志级别为 INFO，WARN 会通过 `CONSOLE` 输出到应用控制台。 |
| 尚未说明报告对路由的影响。 | `createAgent` 调用 `healthCheck(config)` 后没有保存返回的报告，也没有根据 warning 修改 Agent 的就绪状态。 |
| 尚未核对指标采集与故障通知。 | `docker/prometheus.yml` 配置了 Stage 3 的两个采集目标，分别读取 Java 的 `/actuator/prometheus` 和 Go 的 `/metrics`。题给源码与配置未显示这条 Stage 2 warning 被转成指标，也未显示针对它的告警规则或通知接收方。外部部署是否另有日志告警，需要部署侧证据。 |

本题两轮验收已结束。你已独立指出报告未上报，并定位了写入 WARN 日志的代码；控制台输出、指标采集与就绪状态的边界已在上表补全。

---

### T3.4 为可降级故障设计最小告警闭环

- **目标与动作**：这是设计理解题，不要求改代码或连接告警平台。以 T3.1 中“Agent A 注册成功但 RAG 不可用”为唯一场景，结合 healthCheck 现有信号提出一个生产处理方案。假设该业务允许暂时提供无 RAG 的回答，但维护者必须获知故障，并能在后续主动检查证实恢复后关闭告警。
- **要求与提交物**：用一小段话描述从发现此故障到通知、处理和确认恢复的最小告警闭环。
- **通过标准**：方案需要能定位 Agent 与依赖，交代重复故障如何避免反复轰炸、由谁接收并处理，以及恢复如何得到证据并解除告警；必须明确哪些是现有信号、哪些是拟新增机制，不以注册成功或警告消失替代恢复验证。

---

### T4.1 一次 etcd PUT 如何创建 Agent

- **目标与动作**：阅读 EtcdConfigWatcher.handleWatchResponse、convertToConfigEvent、updateCache、notifyListeners，再沿 ConfigEvent 和 DynamicConfigManager.onConfigChange、handleAgentConfigChange 到 AgentRegistry.register。假设 Watch 已成功启动，缓存中不存在 /bitguide/config/agents/translator，收到该 key 的有效 Agent JSON，JSON.id 也为 translator，模型引用存在且创建正常。
- **要求与提交物**：用一小段话说明这条新增配置从 Watch 事件到可查询 Agent 的流转。
- **通过标准**：需要交代事件分类、资源类型和 ID 的来源、JSON 解析以及创建登记的衔接；不能把 Watch 通知本身当成 Agent 已成功上线，也不要求实际推送 etcd。

#### T4.1 首次作答记录

**用户原文**

> 状态流转为： **handleWatchResponse -- notifyListeners -- onConfigChange -- handleAgentConfigChange -- agentRegistry.register**

**模型改正建议**

你列出的五个方法顺序正确，覆盖了从 Watch 回调到注册入口的主调用链。还需解释每一步处理的数据，以及 Agent 何时变得可查询。

**待第二次作答。** 请沿同一个 key 补两句。第一句看 `convertToConfigEvent` 和 `updateCache`：缓存中没有这个 key 时，etcd PUT 被归为哪种配置事件，缓存更新与通知谁先发生？第二句分清 key 和 value 各自的作用：资源类型和 ID 从哪里取，JSON 解析成什么对象，`register` 如何把它变成可按 `translator` 查询的 Agent？

---

### T4.2 无效 JSON 会影响配置缓存还是注册表

- **目标与动作**：阅读 EtcdConfigWatcher.handleWatchResponse 中更新缓存与通知的顺序，再阅读 DynamicConfigManager.parseAgentConfig 和 handleAgentConfigChange。假设 Agent A 已注册且 Watch 缓存中已有 A 的 key；本次收到该 key 的 PUT，但新值是无法解析的 JSON，且无其他并发操作。
- **要求与提交物**：用一小段话解释此事件处理后配置缓存与已注册 Agent 分别会怎样。
- **通过标准**：需要根据事件转换、缓存更新和解析失败分支分别判断两处状态，说明为何两者不必一致；不能把解析失败描述成成功热更新，也不能凭空添加回滚或重试。

---

### T4.3 etcd 初始加载超时是否真的回退 YAML

- **目标与动作**：对照 §5.8，阅读 Stage2App.main、DynamicConfigManager.init、EtcdConfigWatcher.start 与 loadAllConfigs。假设静态配置文件正常加载，etcd 启用且客户端对象创建正常，但初始 get 等待抛出 TimeoutException；之后 startWatching 正常返回，暂未收到任何 Watch 事件。
- **要求与提交物**：用一小段话说明应用如何继续启动，以及当前入口是否会因此将 YAML 中的 agents 注册为后备 Agent。
- **通过标准**：需要指出超时实际被哪层捕获、后续启动位置以及 Agent 注册的调用证据；区分加载静态基础配置与注册 YAML Agent，不能仅按日志中的“等待”推定已实现主动重连或完整回退。

---

### T4.4 Skill 依赖为何必须在 init 前注入

- **目标与动作**：阅读 Stage2App.main 中 setSkillManager 与 init 的顺序，再沿 DynamicConfigManager.init → EtcdConfigWatcher.start、loadAllConfigs → handleSkillConfigChange。本题假设初次加载会收到一条有效 Skill 配置；设想将 setSkillManager 移到 init 返回之后，且之后没有新的配置事件。只做静态分析，不实际修改代码。
- **要求与提交物**：用一小段话解释这个顺序变化对初次 Skill 注册的影响。
- **通过标准**：需要关联初次事件发送时机、skillManager 空值分支和 setter 的实际行为；不能假定依赖稍后注入就会自动重放此前事件。

---

### T4.5 知识库配置更新是否等于索引已就绪

- **目标与动作**：阅读 DynamicConfigManager.handleKnowledgeBaseConfigChange。假设一条有效 PUT 配置含非空 sources，ragService 已注入，后台线程已启动，但其中 ragService.initKnowledgeBase 抛出 Exception，且没有其他更新。
- **要求与提交物**：用一小段话解释失败发生时配置缓存、后台工作和对外可确认的状态之间的关系。
- **通过标准**：需要说明缓存写入与后台执行的先后、异常处理位置，以及现有代码能否证明文档已摄取或配置已回滚；不能把“知识库配置已更新”日志当成索引就绪信号。

#### T4.5 首次作答记录

**用户原文**

> 具体位置应该在case PUT:
>
>             case UPDATE:
>
>                 KnowledgeBaseConfigWrapper kbConfig = parseKnowledgeBaseConfig(*event*.getNewValue());
>
>                 if (kbConfig != null) {
>
>                     knowledgeBaseConfigs.put(kbId, kbConfig);
>
>                     ConsoleLog.info("[Dynamic]", "知识库配置 [%s] 已更新", kbId);
>
>                     *// 动态触发文档摄取*
>
>                     if (ragService != null && kbConfig.sources != null && !kbConfig.sources.isEmpty()) {
>
>                         new Thread(() -> {
>
>                             try {
>
>                                 ConsoleLog.info("[RAG]", "开始加载知识库 [%s] 的文档...", kbId);
>
>                                 KnowledgeBaseConfig kbCfg = toKnowledgeBaseConfig(kbConfig);
>
>                                 int totalChunks = ragService.initKnowledgeBase(kbCfg);
>
>                                 ConsoleLog.ragIndex(kbId, totalChunks);
>
>                             } catch (Exception *e*) {
>
>                                 ConsoleLog.warn("[RAG]", "知识库 [%s] 加载失败: %s", kbId, e.getMessage());
>
>                             }
>
>                         }).start();
>
>                     }
>
>                 }
>
>                 break;
>
> 当发生异常的时候会在后台线程内部catch住，并且记录日志。配置缓存不知道是什么，但是后台线程会正常结束，并且记录错误日志供外部可观测、。

**模型改正建议**

你定位对了：异常在后台线程里被捕获，线程写一条 WARN 后结束。这里的“配置缓存”具体指 `DynamicConfigManager` 的内存表 `knowledgeBaseConfigs`。键 `kbId` 标识知识库，值 `kbConfig` 是从事件 JSON 解析出的配置，包含要摄取的文档来源 `sources`。`put` 发生在启动线程之前，所以“配置已更新”这条日志能证明新配置已经写入内存表。

**待第二次作答。** 假设没有其他更新，看一眼 `catch` 和 `ragIndex` 的位置，用一句话说清：摄取抛错后，按 `kbId` 还能读到哪份配置？此时能否确认这份配置对应的文档已全部摄取、索引已就绪？

#### T4.5 第二次作答记录

**用户原文**

> 能够通过kbId读取到对应的配置，但是不能将读取到配置和索引已经就绪划等号

**模型补充与完善**

| 可再明确之处 | 完整理解 |
| --- | --- |
| “对应的配置”具体是哪一版？ | 本次 PUT 的新配置在线程启动前已写入缓存。后台摄取抛错后，`catch` 只记录 WARN，没有回滚缓存，因此题设下按 `kbId` 仍能读到新配置。 |
| 失败后能确认多少摄取进度？ | `initKnowledgeBase` 抛错后不会执行后面的 `ragIndex(kbId, totalChunks)`。可以确认本次调用失败，无法从这条路径判断失败前已摄取多少文档，也无法确认索引已就绪。 |

**验收通过。** 两轮回答合起来已说明后台异常的处理位置，以及配置可读与索引就绪之间的区别。

---

### T4.6 模型配置更新是否会改变已有 Agent

- **目标与动作**：阅读 DynamicConfigManager.handleModelConfigChange、AgentFactory.createModel 和 setDynamicConfigManager，再在 Stage2App.main 中检查工厂与动态配置管理器的实际接线。假设已有 Agent A 使用静态模型 M，随后收到 M 的有效模型 UPDATE，没有 Agent 配置事件，也没有额外调用 setter。
- **要求与提交物**：用一小段话判断这条模型事件是否足以让已有 A 的后续请求使用新模型，并说明依据。
- **通过标准**：需要区分动态配置缓存、工厂创建时取配置和已有 Agent 持有的 model；结论必须考虑入口是否完成依赖接线，不将文档中的实时路由图当作已发生的字段替换。

#### T4.6 首次作答记录

**用户原文**

> handleModelConfigChange(*event*);完成之后必须要再handleAgentConfigChange(*event*);才能触发agent的update，使用新的model

**模型改正建议**

你判断对了第一步：单独收到模型 UPDATE 时，管理器只更新 `modelConfigs`，已有 A 仍持有创建时的 `model`。需要修正的是“再触发 Agent 更新就会使用新模型”。两个处理方法分别接收对应类型的配置事件。即使随后收到有效的 Agent UPDATE，且 A 仍引用 M，重建时使用哪份 M 配置也取决于工厂。Stage2App.main 将工厂传给了管理器，却没有调用工厂的 `setDynamicConfigManager`。因此工厂的 `createModel` 会跳过动态缓存，仍按静态 M 创建模型。

首次作答部分正确。请用一小段话再答一次，说明这次 UPDATE 改了哪里、已有 A 为什么继续使用原模型，以及 Stage2 的接线如何影响重建时的配置来源。

---

### T5.1 引用计数在什么时候真正起作用

- **目标与动作**：阅读 AgentRegistry.get、acquire、release 和 AgentEntry.acquire、release、waitForRelease。假设无并发更新，调用方先通过 acquire 得到条目，稍后在该条目上 release；对照只使用 get 的调用方。本题只讨论正常借用与归还，不分析竞态。
- **要求与提交物**：用一小段话解释两种获取方式对活动引用计数的影响，以及等待请求完成依赖什么调用约定。
- **通过标准**：需要把计数变化与具体方法对应，说明获取、使用、归还之间的责任；不能把 ConcurrentHashMap 或普通 get 等同于自动记录正在执行的请求。

#### T5.1 首次作答记录

**用户原文**

> get：直接获取，不增加引用计数
>
> acquire：获取的同时通过AgentEntry增加当前的agent 的引用计数

**模型改正建议**

你对获取阶段的两项判断都正确。成功调用 `acquire` 后，调用方拿到的是计数已加一的 `AgentEntry`。还需补上归还与等待的关系：调用方应在用完 Agent 后对同一条目调用一次 `release()`，让计数减一。`waitForRelease` 根据该条目的计数是否归零判断借用是否全部结束，所以只走 `get` 的请求即使还在执行，等待方也无法通过计数看到它。

首次作答部分正确。请用一小段话把获取、使用和归还连起来，说明在什么调用约定下，计数归零才代表所有使用者都已用完 Agent。

#### T5.1 第二次作答记录

**用户原文**

> （1）”调用方应在什么时候对哪个条目调用 `release()`  “不懂\
> （2）”为什么 `waitForRelease` 看不到只通过 `get` 使用 Agent 的请求  “因为在\`waitForRelease\`   方法当中回去判断当前agent的引用计数是否为0，返回布尔值变量。但是get方法不会增加这个引用计数

**模型补充与完善**

| 不足 | 完善理解 |
| --- | --- |
| 还不清楚谁负责归还、何时归还，以及对哪个对象调用 `release()`。 | 假设处理请求的代码执行 `AgentEntry entry = registry.acquire("A")` 并成功拿到条目。此时这次借用已经让 `entry.refCount` 加一。请求通过 `entry.getAgent()` 使用 A，等这次请求用完 A，就对手里保存的同一个 `entry` 调用一次 `entry.release()`，把这次加的一减回来。同步调用可以把归还放进 `finally`，这样处理成功或抛出异常都能归还。 |

第（2）点已答对。`waitForRelease` 查看条目的引用计数，而 `get` 不增加计数。因此即使一个通过 `get` 拿到 A 的请求还在运行，计数也可能为零。

本任务两轮验收已结束。你已解释清楚 `get` 与等待结果的关系；归还约定由模型补全，独立掌握尚未证实。

---

### T5.2 热更新创建失败后旧 Agent 还能否被查到

- **目标与动作**：阅读 AgentRegistry.update、get、exists 和 AgentEntry.markForDeletion。假设 A 已注册，新配置与旧配置不同，旧条目引用数为零；更新时 agentFactory.createAgent 抛出 Exception，且没有其他操作。
- **要求与提交物**：用一小段话说明更新返回后旧条目与通过注册表查询到的可用性分别是什么状态。
- **通过标准**：需要按照标记、等待、创建、替换和 catch 的顺序判断状态，检查是否存在撤销标记的代码；不能将新建失败自动解释成旧 Agent 已恢复服务。

#### T5.2 首次作答记录

**用户原文**

> 当**update方法执行的时候，进入waitForRelease，也就是等待旧条目的引用计数为0，当acquire当前的agent的最后一个请求也通过release方法释放对于oldEntry的引用之后，也就是题目的条件”当前引用计数”，那么会通过notifyAll();唤醒wait(deadline - System.currentTimeMillis());的线程（后续没动作所以我也不知道这么设计的原因是什么），然后shutdownAgent关闭最后的引用，至此，旧条目已经不存在缓存当中了。**
>
> 但是题目的条件表明新的agent创建时候抛出异常，那么注册表缓存当中针对当前的agentId就不存在可用的agent了。

**模型改正建议**

你答对了查询结果。需要修正的是旧条目在 `agents` 中的状态。`markForDeletion()` 给旧条目加上待删除标记。题设给定此时引用计数已经为零，所以 `waitForRelease()` 跳过循环，直接返回 `true`。如果原先有请求占用旧条目，最后一个请求释放时调用的 `notifyAll()` 能让等待线程提前醒来，重新检查计数并继续执行更新。

`createAgent` 抛异常后，控制流直接进入 `catch` 并返回 `false`。后面的 `agents.put` 和 `shutdownAgent(oldEntry)` 都没有执行，异常处理也没有撤销标记。因此原 ID 仍映射到旧条目，旧 Agent 尚未被这次更新关闭。查询时，`get` 遇到待删除标记返回 `null`，`exists` 返回 `false`。

首次作答待修正。第二次只需用一小段话串起创建抛错后保留的映射、标记和查询结果。

#### T5.2 第二次作答记录

**用户原文**

> “旧条目为什么仍在注册表中，却已经查不到”的原因是**get或者exists方法都要判断当前获取到的entry有没有markForDeletion这个标记。**
> 还有就是我不明白`notifyAll`  和`waitForRelease`  存在的意义是什么。不论`waitForRelease`  执行的结果是true还是false，最终都会更新，所以为什么还要设置这个呢？

**模型补充与完善**

| 不足 | 完善理解 |
| --- | --- |
| 已说明查询会检查待删除标记，还缺少旧映射得以保留的原因。 | `createAgent` 抛异常时，后面的 `agents.put` 和 `shutdownAgent(oldEntry)` 都还没执行。`catch` 返回 `false`，保留原映射和标记，因此 `get` 返回 `null`，`exists` 返回 `false`。 |
| 已发现两种等待结果都会继续尝试更新，还需区分继续尝试的时机。 | 旧请求仍持有引用时，更新线程在 `wait` 处释放条目锁并暂停。最后一次 `release` 使计数归零，调用 `notifyAll`。更新线程重新获得锁，检查计数后即可继续。如果等到超时仍有引用，就记录警告并继续尝试创建；创建成功后可能在旧请求仍使用 Agent 时关闭它。题设中计数本来就是零，直接跳过等待。 |

两轮验收已结束。查询行为已说明，异常后保留映射的原因由模型补全。

---

### T5.3 同 ID 热替换会清理哪一代 MCP Client

- **目标与动作**：阅读 AgentRegistry.update、shutdownAgent，以及 AgentFactory.connectInlineMcpServers、destroyAgent。假设 A 的旧内联 Client 为 C1，新配置创建的 Client 为 C2，连接和更新步骤均正常，无并发操作；追踪 inlineMcpClients 中键 A 在创建 C2 前后及清理旧 Agent 时的值。
- **要求与提交物**：用一小段话说明清理旧 Agent 时按 ID 取到的是哪个 Client，并判断资源归属是否与设计意图一致。
- **通过标准**：需要依据 put、remove 和 shutdownAgent 的实际调用顺序追踪对象身份，区分不同 Agent ID 的连接隔离与同 ID 不同版本的资源归属；不要求修复代码，也不推断未检查的底层连接释放行为。

#### T5.3 首次作答记录

**用户原文**

> 当热替换当前agent的配置（mcpClient）的时候，会进入到if (existing != null) {
>
>                 *// 检查配置是否相同*
>
>                 if (existing.isSameConfig(*config*)) {
>
>                     log.debug("Agent [{}] 配置未变化，跳过注册", *agentId*);
>
>                     return true;
>
>                 }
>
>                 *// 配置不同，需要更新*
>
>                 log.info("Agent [{}] 已存在但配置不同，执行更新", *agentId*);
>
>                 return update(*agentId*, *config*);
>
>             }分支，创建新的agent的时候会带上新的MCPClient，具体是&#x5728;*// 3. 装配 ToolManager*
>
>         ToolManager toolManager = createToolManager(*config*);完成的。
>
> 针对旧的agent，会在if (agentFactory != null) {\
> &#x20;           agentFactory.destroyAgent(agent.getId());\
> &#x20;       }内部关闭内联MCP的链接。
>
> 也就是说，在热替换同一个agentId的时候，创建的mcpClient是两个完全独立的对象，彼此之间不会复用

**模型改正建议**

你正确指出 C1 和 C2 是分别创建的对象，也找到了清理入口。还需说明调用 `disconnectAll()` 的具体对象。`shutdownAgent(oldEntry)` 拿到旧条目，但传给 `destroyAgent` 的只有 ID A。工厂通过 `inlineMcpClients.remove(A)` 取回当时映射的值。不同 ID 可以分别保存 Client，同一个 ID 的两代 Client 共用一个键。

首次作答待补充。第二次请用一句话串起这段顺序：创建 C2 时 `put(A, C2)` 如何改变原来的映射，随后清理旧 Agent 时 `remove(A)` 返回谁，以及这个结果是否符合清理旧资源的意图。

#### T5.3 第二次作答记录

**用户原文**

> 这里是不是有一个bug。
> 因为在update的过程当中，实现进行的新的agent的创建 Agent newAgent = agentFactory.createAgent(*newConfig*);，然后再去关闭久的agent：*// 5. 关闭旧 Agent*
>             shutdownAgent(oldEntry);。
> 但是，在创建新的agent 的时候，会以agentId为key，将MCPClient的花奴才能存入工厂的inlineMCPClient缓存当中去，因此，后续关闭旧的agent的时候使用的是同样的agentID来获取的MCPClient，因此会把新创建的agent使用的新的MCPCLient给误删除。

**模型补充与完善**

| 不足 | 完善理解 |
| --- | --- |
| 已正确定位误删的是 C2，可进一步明确“误删除”的具体效果。 | 创建新 Agent 时，`put(A, C2)` 覆盖原映射。清理旧 Agent 时，`remove(A)` 移除映射并返回 C2，随后工厂对 C2 调用 `disconnectAll()`。旧 Client C1 没有被这次清理调用处理。 |

第二轮提示后通过，本题验收结束。

---

### T5.4 CLI 是否会自动使用热替换后的 Agent

- **目标与动作**：阅读 Stage2App.startCLI 中 currentAgent 的赋值和发送消息位置，对照 AgentRegistry.get、update。假设 CLI 已选中 A，持有旧实例 A1；注册表随后成功换成 A2。用户不输入 /switch，直接再发送普通消息；本题不涉及 MCP 资源清理。
- **要求与提交物**：用一小段话判断这次普通消息使用哪个实例，以及这条 CLI 路径是否参与引用计数。
- **通过标准**：需要以保存的对象引用、重新查询发生的位置和实际调用方法为依据；不能把文档 §5.7 的概念图理解成每条消息都会刷新注册表引用，也不能把注册表已替换等同于所有调用方已切换。

## 通关记录

共 19 题。T1.1、T1.2 均为两轮结束，模型已补全，独立掌握尚未证实；原文与反馈见对应任务下方；其余 17 题均未验证。不再复测 T1.1、T1.2，后续按任务记录实际证据与轮次，不预填用户回答或掌握结论。

