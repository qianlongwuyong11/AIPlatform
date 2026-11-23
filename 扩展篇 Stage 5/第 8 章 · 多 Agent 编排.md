# 第 8 章 · 多 Agent 编排：三种拓扑 + 默认单 Agent 门禁 + 主动 Agent

> **为什么有这章**：原项目已有自研工作流引擎（Supervisor 并行 / Router 路由 / 子任务外包 / Checkpoint 续跑）——编排底子不薄，但缺两样：① "默认单 Agent 门禁"（防无脑上多 Agent，顺序推理任务反而降 39-70%）；② "主动 Agent"（Cron/Heartbeat 定时巡检）。这章补这两个 + 子 Agent 即工具；对应学员反馈"多 agent，掌握 Claude Code / Codex 原理"。

## 这篇讲什么

多 Agent 编排要解决的问题是：**什么时候该用多个 Agent 协作、用了之后怎么协调不失控**。

这里有个反直觉的点得先讲清楚：多 Agent 不是越多越好。多数任务是顺序推理，硬上多 Agent 性能反而降 39-70%（Google DeepMind 180 种配置实测）——协调本身有固定成本（5 个 Agent 约 200ms，50 个超过 2 秒）。所以编排的第一原则是"默认单 Agent，过门禁才升级"。

好消息是项目里已有自研工作流引擎（Supervisor 并行 / Router 路由 / 子任务外包 / Checkpoint 续跑），底子不薄。这章补的是"门禁"和"主动 Agent"。

**核心模型：默认单 Agent + 三问门禁。**

```Plaintext
请求 → 默认单 Agent →【三问门禁】真并行? 需不同专业? 单Agent有瓶颈?
                         │ 都过才升级（控 3-5 个）
                         ▼
           Supervisor并行 / Router路由 / SubTask外包  +  AgentTool(子Agent即工具)
           主动 Agent：Cron/Heartbeat 定时巡检         Checkpoint 崩溃续跑
```

**三个关键认知（想通这三个，这章就懂了）：**

- **先想清楚"该不该上多 Agent"，再想"怎么上"**：三问门禁——子任务真并行（前/后端/测试）、需要不同专业、单 Agent 真有瓶颈，三个都过才升级，且压在 3-5 个（DeepMind 给的甜点区间，超了协调开销就盖过并行收益）。

- **协调优先异步，避开实时**：多 Agent 之间优先用文件或共享知识做异步协调；实时通信成本高，还会放大错误（有研究测到 17 倍）。需要来回确认的才走实时通道。

- **子 Agent 就是个工具**：我们把子 Agent 建模成普通工具（AgentTool），走和内置工具一样的权限和审计管道，异步派发。不用单独维护一套编排层。

> 这是骨架。三种拓扑怎么选、主动 Agent 怎么落地，往下看 §8.2 起。**不用背拓扑名字**，记住"默认单 Agent + 三问门禁"这个判断框架就够了。

---

## 8.1 痛点：什么时候该上多 Agent？怎么编排不失控？

学员反馈原话：

> "多 agent，要掌握 Claude Code / Codex 原理。"

面试官的连环追问：

```Plaintext
你用过多个 Agent 协作吗？
   └─ 什么场景才需要多 Agent？（不是越多越好吧？）
       └─ 多个 Agent 怎么通信？星型还是 P2P？实时还是异步？
           └─ 它们的上下文隔离吗？共享状态怎么防冲突？
               └─ 协调开销多大？50 个 Agent 会不会卡死？
                   └─ Agent 能不能主动干活（定时巡检），不用等人问？
```

**现状诊断：自研工作流引擎已对标大半（这是优势，不是缺口）**

基于真实代码核对（Java `workflow` 包 + Go `pkg/workflow`）：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

> 框成设计：我们的工作流引擎（Graph + Supervisor/Router/SubTask/Loop/Interrupt + Checkpoint）已是简历亮点。扩展篇做两件事：① 加"默认单 Agent 门禁"——用数据说明何时才该升级到多 Agent；② 补"主动 Agent"（Cron/Heartbeat）。学员面试直接说"我们自研工作流引擎，默认单 Agent + 过门禁才多 Agent，支持主动 Agent"。

---

## 8.2 三大 Agent 的多 Agent 协作（重点：讲透）

### 8.2.1 核心问题：编排四要素

多 Agent 协作都回答四个问题：**拓扑 / 通信载体 / 上下文隔离 / 结果汇总**。三大 Agent 的分歧就在这四点：

```Plaintext
编排四要素：
   ①【拓扑】        星型（主 Agent 居中）/ P2P（直连）/ 完全隔离
   ②【通信载体】    实时消息 / 文件（异步）/ 共享知识库
   ③【上下文隔离】  继承父上下文 / 独立上下文 / 完全隔离
   ④【结果汇总】    主 Agent 收 / 共享文件 / 知识沉淀

   → 选择取决于：子任务依赖强度 + 是否需独立视角 + 是否真并行
```

### 8.2.2 默认单 Agent（黄金论据，先讲这个）

**这是编排最重要的认知**：多 Agent 不是默认选项。Google DeepMind 的实测数据很硬：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

**单 Agent 更优的场景**（面试要能答）：

```Plaintext
单 Agent 更优：顺序依赖任务 / 修改共享状态（同文件并发冲突）/
              任务不够复杂 / 调试优先（线性轨迹易定位）
多 Agent 价值：真并行子任务（前/后端/测试）/ 需独立视角验证 /
              不同领域专业分工 / 多维度持续监控
```

### 8.2.3 OpenClaw —— SubAgent 星型 + 路由 Agent + 文件协调

```Plaintext
OpenClaw 两层编排：
   层1 SubAgent（会话内）：sessions_spawn 派生，非阻塞（派完不等）
        星型拓扑：子 Agent 只向主 Agent 汇报，彼此不直连
        定位：快速、聚焦、外包、汇报即结束
   层2 路由 Agent（Gateway）：bindings 把渠道/用户路由到独立 Agent
        每个 Agent 独立 workspace / sessions / auth profiles
        Agent 间不共享记忆，通信走 webhook / MQ

   通信载体：文件（一个写、一个读）—— 异步，无实时反馈
   硬上限：maxConcurrent = 8（对"协调失控"的隐性承认）
```

### 8.2.4 Claude Code —— Subagents + Agent Teams（P2P）+ Worktree

```Plaintext
Claude Code 两层编排：
   Subagents：会话内派发，只向主 Agent 汇报，不互通（快/聚焦）
   Agent Teams（实验性）：多个独立实例组队，P2P 直连（不经 Team Lead）
        定位：跨前/后端/测试协调、相互挑战方案、真并行
   通信载体：共享文件（类比共享文档实时可见）
   冲突解决：Worktree 模式 —— 每个 Teammate 独立 Git 分支，成熟后合并
            （防同时改同一行互相覆盖）

   选择标准三档：
      Subagents（快/聚焦/不互通/只汇报）
      Agent Teams（跨层协调/需互通/可并行/弱依赖）
      单 Session（顺序任务/改同一文件/强依赖）

   生产甜点：2-5 个 Teammate，每人 5-6 任务（超 5 协调开销 > 并行收益）
   与 DeepMind 一致：3-5 个 Agent 优于大型团队
```

### 8.2.5 Hermes —— 完全隔离 + Skills 共享 + PLUR

```Plaintext
Hermes 编排哲学：不通话，靠积累共同经验
   子 Agent 完全隔离：独立对话线程（不继承父历史）、独立终端
   不靠实时通话协调，靠 Skills 共享知识库沉淀经验
   PLUR 插件：纠正一个 Agent 的做法 ──自动同步给同项目其他 Agent──▶
   一 Gateway 一 Agent：多 Agent 需多实例（成本显式，抑制滥用）
   通信载体：execute_code 的 Python RPC（号称零上下文成本）

   取舍：牺牲实时协调，换经验复用——
        "今天一个 Agent 踩的坑，明天所有 Agent 都绕过去"
```

### 8.2.6 子 Agent 即工具（AgentTool）+ 主动 Agent

两个跨框架的重要模式：

```Plaintext
模式A：子 Agent 即工具（AgentTool，Claude Code）
   派生子 Agent ──建模为注册表里的一个普通工具──▶
   生成子 Agent 和调用文件读取 ──走同一权限管道、同一拦截机制──▶
   无需单独的 Agent 编排层（降低架构复杂度）
   主 Agent 不等子 Agent 完成即继续（异步回调）

模式B：主动 Agent（OpenClaw Heartbeat / Hermes Cron）
   OpenClaw：心跳 30 分钟触发新 Session，读 HEARTBEAT.md 决定是否行动
   Hermes：scheduler.tick() 是 Gateway【主事件循环】的一部分（一等公民）
           Cron 任务在【全新会话】执行（不阻塞，用时间换空间）
           Cron 可附加 Skills（定时用特定经验，随积累质量提升）
   → Agent 不只被动等用户问，能主动巡检/回查
```

### 8.2.7 横向对比 + 关键设计点

| **维度** | **OpenClaw** | **Claude Code** | **Hermes** |
| --- | --- | --- | --- |
| **默认立场** | 可以做，非默认 | 单 Agent 优先 | 一 Gateway 一 Agent |
| **拓扑** | SubAgent 星型 + 路由 | Subagents + Teams(P2P) | 完全隔离 |
| **通信** | 文件 | 共享文件 + Worktree | Skills 共享 + PLUR |
| **派发** | sessions_spawn（非阻塞） | AgentTool（异步） | execute_code RPC |
| **上限/甜点** | maxConcurrent=8 | 2-5 Teammate×5-6 任务 | 多实例（成本显式） |
| **主动能力** | Heartbeat（旁路） | 无原生 | Cron 一等公民 |

---

## 8.3 选型：服务端多租户语境下的编排

> 关键差异：本机单用户，**多 Agent 是个人开发场景（前/后端/测试并行），协调靠共享文件/Worktree**；**服务端多租户，多 Agent 是租户/渠道路由 + 主动巡检 + 跨租户调度**——bindings 路由是原生多租户能力，主动 Agent（Cron）做平台巡检，协调开销要纳入配额。

| **维度** | **本机单用户** | **服务端多租户** |
| --- | --- | --- |
| **场景** | 个人开发并行 | 租户/渠道路由+主动巡检 |
| **协调** | 共享文件/Worktree | 跨租户调度+配额 |
| **主动** | 无 | Heartbeat/Cron定时巡检 |

**选型结论**：

```Plaintext
我们的编排 = 自研工作流引擎（已有）+ 默认单 Agent 门禁 + 主动 Agent

  默认：单 Agent（多数任务是顺序推理）
  门禁：三问（真并行？需不同专业？单 Agent 有瓶颈？）过了才升级
  拓扑：Supervisor（并行）/ Router（路由）/ SubTask（外包）—— 已有 patterns
  子 Agent：AgentTool（即工具，复用权限管道）
  主动：Cron/Heartbeat（scheduler.tick 主循环）—— 扩展篇补
  续跑：Checkpoint 持久化（已有）
  配额：协调开销纳入租户配额（接 ch4）
```

---

## 8.4 适配：本机 → 服务端多租户

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| **三大 Agent（本机）** | **AgentForge（服务端多租户）** | **搬/改/弃** |
| --- | --- | --- |
| **默认单 Agent + 门禁（CC）** | 直接用 | **搬** |
| **Supervisor 并行 / Router 路由** | **已有**（workflow patterns） | **已有** |
| **AgentTool（子 Agent 即工具）** | 直接用（复用权限管道） | **搬** |
| **Cron/Heartbeat 主动（OpenClaw/Hermes）** | 补 scheduler.tick 主循环 | **搬** |
| **Worktree 防冲突（CC）** | 版本化兜底（对象存储版本号） | **改** |
| **Skills 共享 + PLUR（Hermes）** | etcd 广播天然实现（接 ch5） | **搬** |
| **Checkpoint 续跑** | **已有**（RedisCheckpointStore） | **已有** |
| **一 Gateway 一 Agent（Hermes）** | bindings 路由（一租户一 workspace） | **改** |

---

## 8.5 落地：AgentForge 的多 Agent 编排

### 8.5.1 总架构（我们已有的引擎 + 扩展篇补的门禁/主动）

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

### 8.5.2 现状诚实呈现：自研工作流已对标大半

> 与前七章不同，编排这块我们**已有扎实的自研工作流引擎**——这是简历亮点，不是缺口。现状对标的程度：

```Plaintext
已对标（workflow 包）：
   ✅ Graph/Node/Edge/Channel/State/Executor —— 编排骨架
   ✅ SupervisorNode —— 并行（对标 CC Agent Teams 的并行）
   ✅ RouterNode —— 路由（对标 OpenClaw bindings 路由）
   ✅ SubTaskExecutorNode —— 子任务外包（对标 SubAgent）
   ✅ LoopNode / InterruptNode —— 循环 + HITL 中断（对标 Interrupt）
   ✅ Checkpoint(Memory+Redis) —— 持久化续跑（对标行业续跑实践）
   ✅ agent_node / tool_node —— Agent/Tool 作为节点

扩展篇补（门禁 + 主动）：
   ➕ 默认单 Agent 三问门禁 —— 防 39-70% 退化
   ➕ AgentTool（子 Agent 即工具，复用权限管道）
   ➕ 主动 Agent（scheduler.tick + Cron，新会话执行）
   ➕ 协调开销纳配额
```

### 8.5.3 默认单 Agent 三问门禁（扩展篇补，关键实现示例）

> 防止"无脑多 Agent"导致 39-70% 退化。多数任务是顺序推理，单 Agent 就够。

```Java
// Java：多 Agent 门禁——三问过了才升级
public class MultiAgentGatekeeper {
    public Decision shouldUseMultiAgent(TaskSpec task) {
        // 三问门禁
        if (!task.trulyParallel())      return Decision.SINGLE;  // ① 真并行？否则顺序退化
        if (!task.needsDistinctExpertise()) return Decision.SINGLE; // ② 需不同专业？
        if (!task.singleAgentBottleneck())  return Decision.SINGLE; // ③ 单 Agent 有瓶颈？
        if (task.estimatedAgents() > 5)
            log.warn("超 5 个 Agent，协调开销可能 > 并行收益（DeepMind）");
        return Decision.MULTI;
    }
}
```

```Go
// Go：多 Agent 门禁
func (g *MultiAgentGatekeeper) ShouldUseMultiAgent(t TaskSpec) Decision {
    if !t.TrulyParallel()          { return Single } // ①
    if !t.NeedsDistinctExpertise() { return Single } // ②
    if !t.SingleAgentBottleneck()  { return Single } // ③
    if t.EstimatedAgents() > 5 {
        log.Warn("超 5 个 Agent，协调开销可能超并行收益")
    }
    return Multi
}
```

```Python
class MultiAgentGatekeeper:
    def decide(self, task: TaskSpec) -> Decision:
        # 三个条件任意一个不成立，都保留单 Agent，避免无效协调开销。
        if not task.truly_parallel:
            return Decision.SINGLE
        if not task.needs_distinct_expertise:
            return Decision.SINGLE
        if not task.single_agent_bottleneck:
            return Decision.SINGLE
        if task.estimated_agents > 5:
            logger.warning("Agent 数量较高，需评估协调成本")
        return Decision.MULTI
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **默认单 Agent** | 三问不过就单 Agent | 防 39-70% 退化（黄金数据） |
| **三问门禁** | 真并行/不同专业/单 Agent 瓶颈 | 明确升级条件 |
| **超 5 告警** | 协调开销 > 并行收益 | 甜点配置（CC 2-5） |

### 8.5.4 三种拓扑落到 workflow patterns（已有 + 对标）

```Plaintext
我们的 workflow patterns ↔ 三大 Agent 拓扑：
   SupervisorNode     ──对标──▶ Claude Code Agent Teams（并行）
   RouterNode         ──对标──▶ OpenClaw bindings 路由（按租户/渠道）
   SubTaskExecutorNode──对标──▶ Subagents（外包，只汇报）
   InterruptNode      ──对标──▶ HITL 中断（接第3章富文本审批）
   LoopNode           ──对标──▶ ReAct 迭代（接第4章迭代预算）

   → 我们不用造新轮子，已有的 patterns 就是行业拓扑的实现
```

### 8.5.5 子 Agent 即工具（AgentTool，复用权限管道，关键实现示例）

> 派生子 Agent 建模为注册表里的普通工具——走同一权限管道（第 3 章），无需独立编排层。

```Java
// Java：AgentTool —— 子 Agent 是 Tool 注册表的一等公民
public class AgentTool implements Tool {
    public String name() { return "spawn_subagent"; }
    public RiskLevel risk() { return RiskLevel.WRITE; }      // 走第3章权限管道
    public ToolResult execute(Map<String,Object> args) {
        Agent sub = agentFactory.create((String)args.get("agentId"));
        auditLogger.append(spawnEvent(args));                // 记审计日志
        return sub.chatAsync((String)args.get("task"));      // 异步，不等完成
    }
}
```

```Go
// Go：AgentTool
type AgentTool struct{ factory *AgentFactory }
func (t *AgentTool) Execute(args map[string]any) (*ToolResult, error) {
    sub := t.factory.Create(args["agentId"].(string))
    audit.Append(spawnEvent(args))                           // 审计
    return sub.ChatAsync(args["task"].(string)), nil         // 异步
}
```

```Python
class AgentTool:
    name = "spawn_subagent"
    risk = RiskLevel.WRITE  # 继续复用工具权限、审批和审计管道

    async def execute(self, args: dict[str, object]) -> ToolResult:
        subagent = await self.factory.create(str(args["agent_id"]))
        await self.audit.append(spawn_event(args))
        # 创建后台任务后返回 execution_id，不伪装成子任务已经完成。
        task = asyncio.create_task(subagent.chat(str(args["task"])))
        execution_id = self.tasks.register(task)
        return ToolResult.pending(execution_id)
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **子 Agent 即工具** | AgentTool 实现 Tool 接口 | 复用权限/审计管道 |
| **异步派发** | chatAsync 不等完成 | 主 Agent 继续别的活 |
| **无独立编排层** | 复用 ToolManager | 架构更简 |

### 8.5.6 主动 Agent（Cron/Heartbeat，scheduler.tick，扩展篇补）

> 现状：Agent 被动等用户消息。扩展篇借鉴 Hermes 把 scheduler 做成主循环一等公民——Agent 能主动巡检（热度刷新/慢 API 回查/配额重置）。

```Plaintext
主动 Agent（scheduler.tick 主循环）：
   DistributedAgentGateway 主事件循环 ──每周期调 scheduler.tick()──▶
      tick ──取调度器锁（防并发同批）──▶ 筛到期任务
           ──每任务：创建【全新会话】（不阻塞当前会话）
           ──加载热记忆 + 相关 Skills（接 ch1/ch5）
           ──执行 → 结果路由到目标 → 算下次时间
   → 用"定时任务替代阻塞等待"（OpenClaw Cron 思路）

   服务端 Cron 用途：
   · RAG 热度刷新（接 ch7，HotChunkOptimizer.RefreshHotZone）
   · 慢 API 回查（当前会话结束，到点新会话接续）
   · 配额重置（接 ch4，TPM 按分钟重置）
   · 定时报表/巡检
```

```Java
// Java：主动 Agent（scheduler.tick 在 Gateway 主循环）
public void onTick() {
    schedulerLock.lock();                         // 防并发同批
    try {
        for (CronJob job : scheduler.dueJobs()) {
            AgentSession fresh = newSession(job.agentId());  // 全新会话，不阻塞
            fresh.loadHotMemory().loadSkills(job.skills());  // 热记忆+Skills
            executeAsync(fresh, job.prompt());                // 到点执行
        }
    } finally { schedulerLock.unlock(); }
}
```

```Go
// Go：scheduler.tick
func (s *Scheduler) Tick() {
    s.lock.Lock(); defer s.lock.Unlock()         // 防并发
    for _, job := range s.DueJobs() {
        fresh := s.newSession(job.AgentID)        // 全新会话
        fresh.LoadHotMemory().LoadSkills(job.Skills)
        go s.executeAsync(fresh, job.Prompt)      // 到点执行
    }
}
```

```Python
class Scheduler:
    def __init__(self) -> None:
        self._tick_lock = asyncio.Lock()

    async def tick(self) -> None:
        # try-lock 语义：上一批未完成扫描时，不并发启动同一批定时任务。
        async with self._tick_lock:
            for job in await self.due_jobs():
                session = await self.new_session(job.agent_id)
                await session.load_hot_memory()
                await session.load_skills(job.skills)
                asyncio.create_task(self.execute(session, job.prompt))
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **scheduler 一等公民** | tick 在主循环 | Agent 能主动干活 |
| **全新会话执行** | 不阻塞当前会话 | 用时间换空间 |
| **热记忆每次加载** | 新会话不丢状态 | 接 ch1 |
| **Cron 可附加 Skills** | 定时用特定经验 | 接 ch5，随积累质量升 |

### 8.5.7 Checkpoint 续跑（已有）

> 已有 `CheckpointStore`（Memory + Redis），崩溃可从断点续跑（对比 OpenClaw"删 sessions.json 失忆"反模式）。

```Plaintext
Checkpoint（已有 RedisCheckpointStore）：
   每步执行后 ──save(executionId, state)──▶ Redis 持久化
   崩溃重启 ──load(executionId)──▶ 从断点续跑
   → 长任务可恢复
```

### 8.5.8 声明式配置（etcd 动态编排策略）

```YAML
# 多 Agent 编排声明式配置（etcd 下发）
orchestration:
  defaultMode: single              # 默认单 Agent
  gatekeeper: true                 # 三问门禁
  maxAgents: 5                     # 甜点上限（超则告警）
  hardLimit: 8                     # 硬上限（对标 OpenClaw maxConcurrent）
  patterns: [supervisor, router, subtask, loop, interrupt]
  subAgentAsTool: true             # AgentTool（复用权限管道）
  proactive:
    enabled: true                  # 主动 Agent
    schedulerTick: true            # scheduler.tick 主循环
    cronJobs:
      - { id: rag_hot_refresh, cron: "0 */6 * * *", skills: [rag-optimizer] }
      - { id: quota_reset,     cron: "* * * * *",   skills: [quota-manager] }
  checkpoint:
    store: redis                   # 持久化续跑
  coordinationCostInQuota: true    # 协调开销纳入配额（接 ch4）
```

> 和核心卖点的关系：编排策略（默认模式/上限/Cron）走 etcd 动态下发，`DynamicConfigManager` 广播。Cron 任务本身也走 etcd（动态加/改定时任务，不重启）。

### 8.5.9 文件清单

| **文件** | **改动** | **说明** |
| --- | --- | --- |
| `MultiAgentGatekeeper`**（新增）** | 三问门禁 | 默认单 Agent，防退化 |
| `workflow/patterns/*`**（已有）** | Supervisor/Router/SubTask/Loop/Interrupt | 三种拓扑（已对标） |
| `AgentTool`**（新增）** | 子 Agent 即工具 | 复用权限/审计管道 |
| `Scheduler`** / **`scheduler.tick`**（新增）** | 主动 Agent（Cron） | 主循环一等公民 |
| `CheckpointStore`**（已有 Redis）** | 不动 | 持久化续跑 |
| `RouterNode`** + bindings** | 按租户/渠道路由 | 原生多租户 |
| **etcd schema** | `orchestration.*` 策略 | 声明式动态编排 |

---

## 8.6 面试答法

| **面试官问** | **标准答法** |
| --- | --- |
| **用过多 Agent 协作吗？** | 用，但默认单 Agent + 三问门禁。自研工作流引擎（Graph + Supervisor并行/Router路由/SubTask/Loop/Interrupt + Checkpoint）。多数任务是顺序推理，多 Agent 反而退化 39-70%（DeepMind 180 配置实测），所以先单 Agent，过门禁（真并行/需不同专业/单 Agent 瓶颈）才升级。 |
| **什么时候才该上多 Agent？** | 三问门禁：① 子任务真并行（前/后端/测试）② 需不同专业分工 ③ 单 Agent 有具体瓶颈。三者都过才升级，且控制在 3-5 个（DeepMind 结论，超 5 协调开销 > 并行收益，5 Agent≈200ms、50 Agent>2s）。 |
| **多 Agent 怎么通信？** | 看拓扑：Supervisor 并行（主 Agent 收）、Router 路由（按租户/渠道）、SubTask 外包（只汇报）。优先文件/共享知识做异步协调（避实时通信成本 + 错误放大最高 17.2 倍），需要来回确认的才走实时通道。 |
| **子 Agent 怎么派生？权限怎么管？** | AgentTool——子 Agent 是注册表里的普通工具，走同一权限管道（第3章三层纵深）。异步派发，主 Agent 不等完成。无需独立编排层，架构更简。 |
| **Agent 能不能主动干活？** | 能，主动 Agent——scheduler.tick 是 Gateway 主循环的一等公民，Cron 定时任务在全新会话执行（不阻塞当前会话）。服务端用来做 RAG 热度刷新、慢 API 回查、配额重置、定时报表。 |
| **多个 Agent 改同一个东西冲突怎么办？** | Worktree 式隔离——每个 Agent 在独立分支/版本工作，成熟后合并，防同时改同一行互相覆盖。共享产出落分布式文件/RAG 知识库，对"同时改同一产物"引入版本号兜底。 |
| **编排崩溃了怎么办？** | Checkpoint 持久化——每步存 Redis（RedisCheckpointStore），崩溃从断点续跑。对比 OpenClaw"删 sessions.json 重启失忆"的反模式，我们长任务可恢复。 |
| **服务端和本机的多 Agent 有什么不同？** | 服务端：① Router bindings 是原生多租户能力（按租户/渠道路由到独立 workspace）② 主动 Agent 做平台巡检（Cron）③ 协调开销纳入租户配额（接第4章）。本机（Claude Code 等）是个人开发并行，没这些。 |

---

## 小结

1. **默认单 Agent，过门禁才升级**：多数任务顺序推理，多 Agent 退化 39-70%（DeepMind 黄金数据）。三问门禁防无脑多 Agent。

1. **三种拓扑已对标**：Supervisor（并行）/ Router（路由）/ SubTask（外包）—— 已有 workflow patterns，对标 CC/OpenClaw/Hermes。

1. **子 Agent 即工具（AgentTool）**：复用权限/审计管道，异步派发，无独立编排层。

1. **主动 Agent（Cron/Heartbeat）**：scheduler.tick 主循环一等公民，全新会话执行不阻塞。

1. **Checkpoint 续跑**：已有 Redis 持久化，崩溃可恢复。

1. **协调开销纳配额**：服务端多 Agent 的协调成本按租户计量（接 ch4）。

---

> 下一章：Gateway 架构——编排管"多个 Agent 怎么协作"，Gateway 管"请求怎么进来、怎么路由、怎么隔离"（控制平面 + 层级 session key + fail-closed）。
