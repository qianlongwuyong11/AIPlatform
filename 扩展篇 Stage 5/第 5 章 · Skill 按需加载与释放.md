# 第 5 章 · Skill 按需加载与释放：渐进式披露 + 可见性裁剪 + 自动沉淀

> **为什么有这章**：原项目 Skill 已有双源加载（文件 + etcd）、双策略匹配（LLM + 关键词）、SkillEnhancer 按 Skill 过滤工具说明——骨架在；但过滤没打通（ReAct 主循环每轮仍全量喂所有工具 schema）、无渐进式披露、无自动沉淀、Skill 卸载只删 key 不释放。这章补**渐进式披露三层 + 可见性裁剪（打通过滤）+ 自动沉淀 + 释放语义**；对应学员反馈"skill 按需加载和释放"（面试官直接问的机制）。

## 这篇讲什么

Skill 系统要解决的问题是：**Skill/工具一多怎么不把上下文撑爆、怎么随用随收、怎么让经验越用越多而不烂掉**——别让 200 个 Skill 启动期就吃掉上百万 Token，也别让一次错误沉淀的经验污染整条流水线。

为什么服务端要单拎出来讲：本机的 Skill 是开发者自己的本地文件，自写自用、质量自己负责；服务端多租户里 Skill 是租户共享、平台 etcd 下发的统一资源——一个坏 Skill（恶意或错误沉淀）会跨租户泄露或污染平台。所以加载方式之外，沉淀还得过质量门禁、Skill 得按租户隔离。

**核心模型：渐进式披露三层 + 可见性裁剪 + 沉淀/释放闭环。** 核心是把"经验规模"和"使用成本"解耦——Skill 不一次性全塞进上下文，而是分层给：

```Plaintext
渐进式披露三层（Skill "按需加载"的标答）：

  [第一层] 启动期   只载【名字+描述】       ~100 T / Skill    知道有哪些经验
  [第二层] 命中时   载完整 SKILL.md         <5000 T / Skill   任务真用到才注入
  [第三层] 辅助脚本 不进上下文，bash 直跑    0 T              LLM 不"读"代码，直接执行

  → 200 个 Skill 启动期只付 200×100 = 2 万 Token（不是 100 万全量预付）

  + 可见性裁剪：SkillEnhancer 过滤的 toolRefs → 接进 ReAct schema（工具面按 Skill 收窄）
  + 沉淀：≥5 次调用 → 质量门禁 → 写回 etcd；释放：注销通知 + 工具回收 + prompt 清理
```

**三个关键认知（想通这三个，这章就懂了）：**

- **经验规模和使用成本解耦，靠的是分层披露**：启动期只付名字层的钱（100T/Skill），命中才给完整内容（5000T），脚本根本不进上下文（0T，bash 直执行）。没有这层，Skill 越多上下文越满，直接掉进第 4 章的工具数量陷阱。

- **可见性裁剪的关键是打通 ReAct schema**：SkillEnhancer 已经按 Skill 的 toolRefs 过滤了工具描述，但只作用在 prompt 文本上；ReAct 主循环每轮仍【全量】把所有工具 schema 喂给 LLM——这层不打通，过滤只做了一半，工具数量陷阱的根就还在。

- **服务端独有的"沉淀质量门禁"**：Hermes 的自动沉淀（≥5 次工具调用就提炼 Skill）很诱人，但没有质量评估会 garbage in、沉淀出错误经验；服务端多租户里一个租户的坏经验不能污染全平台，所以沉淀必须过门禁 + 来源签名 + 按租户隔离。

> 这是这章的骨架。三层披露每层怎么落、可见性裁剪代码怎么改、自动沉淀的触发和质量门禁长什么样，往下看 §5.2 起。**细节是用来查的，不用全记**；先把"分层披露 + 打通 schema + 门禁隔离"这三件事想清楚。

---

## 5.1 痛点：Skill 一多，上下文炸；"释放"只是删个 key

学员反馈原话：

> "Skill 按需加载和释放。"（面试官直接问机制）

面试官的连环追问：

```Plaintext
你的 Skill 怎么加载的？
   └─ Skill/工具一多，全塞进上下文，Token 和决策质量会怎样？
       └─ 你说"按需加载"——具体怎么按需？名字还是内容，什么时候给？
           └─ Skill 不用了怎么"释放"？只是从注册表删掉就完了？
               └─ Agent 正在用的 Skill 被卸了，会出问题吗？
                   └─ Skill 能不能像 Hermes 那样，Agent 干完活自己沉淀出新 Skill？
```

**现状诊断：SkillEnhancer 已按 Skill 过滤，但 ReAct 没打通；"释放"只是删 key**

基于真实代码核对（Java `com.bitguide.skill` + Go `pkg/skill`），现状有个**值得深挖的细节**：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

> 框成设计而非缺陷：Skill 子系统已实现"声明式 Skill = 专业 prompt + 工具引用 + 示例"的增强模式，加载双源、匹配双策略、注入按 Skill 过滤——骨架是好的。扩展篇做两件事：① 把"过滤"从 prompt 文本延伸到工具 schema（可见性裁剪）；② 补齐"释放"和"沉淀"的完整语义。学员面试直接说"我们用渐进式披露 + 可见性裁剪管 Skill"。

---

## 5.2 三大 Agent 的 Skill 系统（重点：讲透）

### 5.2.1 核心问题："经验从哪来"的三种赌注

Skill 系统的根本问题是"**经验从哪来**"，三大 Agent 各押一种赌注：

```Plaintext
Skill 经验来源（三种赌注）：
   OpenClaw    ──赌社区──▶ ClawHub 开放市场（31000+ Skills，即装即用）
   Claude Code ──赌个人──▶ 自写 SKILL.md（经验外化，质量高冷启动慢）
   Hermes      ──赌进化──▶ Agent 自我沉淀（干完活自动提炼，越用越多）

   但"从哪来"只是一半问题；另一半是"装进来后怎么不爆上下文"——
   → Claude Code 给出了最干净的答案：渐进式披露。
```

### 5.2.2 Claude Code —— 渐进式披露三层（本章主角）

Claude Code 的核心：**经验积累越多，使用成本不该随之增长**。靠的是分层加载——不同阶段给不同粒度：

```Plaintext
渐进式披露三层（Progressive Disclosure）：

  [第一层] 启动时      只载【名称 + 描述】   ~100 Token / Skill
           Agent 知道"有哪些经验可用"，但不占大块上下文
                │
                ▼  判断当前任务相关
  [第二层] 按需加载    载完整 SKILL.md 内容  <5,000 Token / Skill
           任务真用到这个 Skill，才把完整方法注入
                │
                ▼  Skill 带的辅助资料/可执行脚本
  [第三层] 不注入上下文 直接 bash 执行        0 Token
           一个 Skill 可带几千行 Python，LLM 不"读"代码，直接跑

  → 200 个 Skill 启动期只付 200×100 = 2 万 Token（名字层）
    而不是 200×5000 = 100 万 Token（全量预付）
```

**Compaction 后的 Skill 持久性保护**（长会话场景的关键）：

```Plaintext
上下文压缩时，已加载的 Skill 怎么办？
   ✅ Claude Code：已加载 Skill【重新附加】到压缩后的上下文
      每个 Skill 保留前 5,000 Token
      所有 Skills 共享 25,000 Token 总预算
   → 压缩不会把"正在用的经验"丢掉，但有总预算上限（防 Skill 自己把上下文占满）

   预算超了怎么办？按相关性裁剪——只留和当前任务最相关的 Skill。
```

> 教学点：**渐进式披露把"经验规模"和"使用成本"解耦**。没有它，Skill 越多上下文越满；有了它，启动期固定只付名字层的钱。这是"按需加载"的标准答案。

### 5.2.3 Hermes —— 自我进化（自动沉淀 + 跨 Agent 传播）

Hermes 押"Agent 能自我进化"——**执行过程本身就是最好的经验来源**：

```Plaintext
Hermes Skill 自动沉淀：
   触发阈值 ──≥5 次工具调用的复杂任务──▶ 系统自动把执行过程提炼成 Skill
   写入路径 ──~/.hermes/skills/──▶
   自我更新 ──Agent 发现更好方法时【自动修改】Skill 文档──▶
   实测：连续几个复杂任务，2 小时内自动生成 3 份 Skill，再跑类似任务速度 +40%

   跨 Agent 传播（PLUR 插件）：
   一个 Agent 的纠正 ──自动传播给同项目其他 Agent──▶
```

```Plaintext
Hermes 的风险（服务端要特别注意）：
   · "5 次工具调用"启发式不精确——什么任务值得沉淀？抽象层次在哪？
   · 无内置质量评估——糟糕执行会沉淀出【错误经验】（garbage in）
   · 自我进化的前提是"每次执行都足够好"，但 Agent 会自我欺骗
   → 服务端多租户里，一个租户沉淀的坏经验不能污染全平台
```

### 5.2.4 OpenClaw —— 社区赌注（冷启动最好，供应链最惨）

```Plaintext
OpenClaw ClawHub：
   31000+ Skills 即装即用（冷启动三框架最好）
   发布门槛 ──仅需注册 >1 周的 GitHub 账号──▶
   无强制审核 / 无签名 / 无沙箱验证 / 无安全扫描
   安装的 Skill 拥有【与 OpenClaw 完全相同的系统权限】

   代价（反面案例）：
   2026-01 发现 341 个恶意包（偷 OAuth Token/API Key/浏览器密码）
   恶意/隐患 Skill 一度 > 总量 20%
   → 赌社区 = 把信任边界一起交出去
   → 供应链治理（来源签名 + 审核）
```

### 5.2.5 横向对比 + 关键设计点

| **维度** | **OpenClaw** | **Claude Code** | **Hermes** |
| --- | --- | --- | --- |
| **经验来源** | 社区（ClawHub 31000+） | 个人自写 SKILL.md | 自我进化（自动沉淀） |
| **加载机制** | 全量 | **渐进式披露三层** | 按任务激活 |
| **压缩保护** | 无（Session 隔离） | 5000T/个，总 25000T 预算 | （未详述） |
| **脚本处理** | — | 不注入上下文，bash 直执行 | — |
| **质量把控** | 无（社区自净） | 作者保证 | 无评估（garbage in 风险） |
| **冷启动** | 最好 | 慢 | 空，靠积累 |

---

## 5.3 选型：服务端多租户语境下的 Skill

> 关键差异：本机单用户，**Skill 是自己的本地文件（**`.claude/skills/`**），自写自用，质量自己负责**；**服务端多租户，Skill 是租户共享 / 平台下发的统一资源**——一个坏 Skill（恶意或错误沉淀）会污染平台或跨租户泄露。所以服务端的 Skill 系统要加**来源治理**和**质量门禁**。

| **维度** | **本机单用户** | **服务端多租户** |
| --- | --- | --- |
| **Skill来源** | 本地文件 自写自用 | etcd下发 租户共享 |
| **沉淀** | 只影响自己 | 要质量门禁+按租户隔离 |
| **安全** | 无跨租户泄露 | 坏经验不能污染全平台 |

**服务端 Skill 的三个独有要求**（面试可答）：

```Plaintext
来源治理   Skill/MCP 来源签名 + 平台审核（防 ClawHub 类）
质量门禁   自动沉淀的 Skill 要过质量评估（garbage in 防护）—— 本章新增
按租户隔离 私有 Skill / 平台共享 Skill 分级，跨租户不可见 —— 接第 2 章
```

**选型结论**：

```Plaintext
我们的 Skill 系统 = 渐进式披露 + 可见性裁剪 + 自动沉淀（带门禁）+ 释放语义

  加载：渐进式披露三层（100T 名字 / 5000T 内容 / 0T 脚本直执行）
  可见：SkillEnhancer 过滤 → 接进 ReAct schema（工具面按 Skill 收窄）
  持久：Compaction 持久性预算（5000T/个，总 25000T）
  沉淀：≥5 次工具调用 → 质量门禁 → 写回 etcd（形成"使用→沉淀→下发"闭环）
  释放：注销通知 + 工具回收 + prompt 清理（非只删 key）
```

---

## 5.4 适配：本机 → 服务端多租户

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| **三大 Agent（本机）** | **AgentForge（服务端多租户）** | **搬/改/弃** |
| --- | --- | --- |
| **渐进式披露三层（CC）** | 直接用 | **搬** |
| **脚本不注入、bash 直执行（CC）** | 直接用（重型工具剥离出上下文） | **搬** |
| **Compaction 5000T 预算（CC）** | 直接用 + 总预算裁剪 | **搬** |
| **自动沉淀 ≥5 次（Hermes）** | 直接用 + **质量门禁** | **搬+加强** |
| **跨 Agent 传播 PLUR（Hermes）** | etcd 广播天然实现 | **搬**（复用现有观察者） |
| **Skill 全集常驻（OpenClaw）** | 改成可见性裁剪 | **改** |
| **社区开放市场（OpenClaw）** | 来源签名 + 审核 | **改** |

---

## 5.5 落地：AgentForge 的 Skill 按需加载与释放

### 5.5.1 总架构

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

### 5.5.2 现状诚实呈现：过滤已做，但没打通（核心教学点）

> 这是本章最值得讲清楚的一点。现状的 `SkillEnhancer` **已经按 Skill 的 **`toolRefs`** 过滤**了注入 prompt 的工具说明，**但 ReAct 主循环每轮仍把所有已注册工具的完整 schema 喂给 LLM**——"过滤"只对 prompt 文本生效，对模型真正能调的工具面没生效。

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

> 框成设计演进：SkillEnhancer 的"按 Skill 过滤"骨架是对的，扩展篇把它从"prompt 文本过滤"延伸到"工具 schema 过滤"——让模型真正只看到 Skill 相关的工具。这不是修 bug，是把已有的好骨架接通。

### 5.5.3 渐进式披露三层（SkillEnhancer 升级，关键实现示例）

> 现状：命中 Skill 时一次性注入完整 systemPrompt + 工具说明。扩展篇升级为三层——启动期只载名字，命中才载内容，脚本不入上下文。

```Java
// Java：SkillEnhancer 渐进式披露三层
public class SkillEnhancer {
    // 第一层：Agent 启动期，所有 Skill 只载 name+description（~100T/个）
    //         —— 已有：SkillManager 启动时注册的就是这些轻量字段

    // 第二层：命中 Skill 时，注入完整 systemPrompt（<5000T）
    public String buildSkillPrompt(Agent agent, Skill skill) {
        String prompt = skill.getSystemPrompt();           // 完整方法
        prompt += toolGuide(skill.getToolRefs());          // 工具使用指引
        return prompt;                                      // 受 Compaction 预算约束
    }

    // 第三层：Skill 带的脚本/辅助资料，【不注入上下文】，工具直接执行
    //         LLM 不"读"代码，ToolManager 直接跑 → 0 Token
}
```

```Go
// Go：渐进式披露三层
func (e *SkillEnhancer) BuildSkillPrompt(ag Agent, s *Skill) string {
    // 第二层：命中才载完整内容
    prompt := s.SystemPrompt
    prompt += e.toolGuide(s.ToolRefs)
    return prompt
}
// 第三层：脚本不入上下文，工具直接执行（0 Token）
```

```Python
class SkillEnhancer:
    def build_skill_prompt(self, skill: Skill) -> str:
        # 第一层已在注册表中：启动期只保存 name 与 description。
        # 第二层仅在命中后读取完整提示词和相关工具说明。
        return skill.system_prompt + self.tool_guide(skill.tool_refs)

    async def run_resource(self, resource: SkillResource) -> ToolResult:
        # 第三层脚本不塞进模型上下文，由工具执行器直接运行，节省 Token。
        return await self.tool_manager.execute_resource(resource)
```

| **层** | **时机** | **粒度** | **Token** |
| --- | --- | --- | --- |
| **第一层** | 启动期 | name + description | ~100/个 |
| **第二层** | 命中时 | 完整 systemPrompt | <5000/个 |
| **第三层** | 调用时 | 脚本不入上下文，直执行 | 0 |

### 5.5.4 可见性裁剪（打通关键，关键实现示例）

> 把 SkillEnhancer 已做的"按 toolRefs 过滤"**接进 ReAct 的 schema**——这是打通上面 §5.5.2 脱节的关键一步。

```Java
// Java：ReAct 循环不再全量喂 schema，按 Skill 的 toolRefs 裁剪
private String chatWithTools(List<ChatMessage> history, String sessionId, Skill activeSkill) {
    // 按当前 Skill 裁剪可见工具（而非全集）
    List<ToolSpecification> visible = activeSkill == null
        ? baseTools                                   // 无 Skill 命中：基础工具集
        : filterByToolRefs(toolSpecifications, activeSkill.getToolRefs());
    while (iteration < maxIterations) {
        ChatRequest req = ChatRequest.builder()
            .messages(history)
            .toolSpecifications(visible)              // ← 受控子集，非全集
            .build();
        ...
    }
}
```

```Go
// Go：可见性裁剪（接进 GetToolDefinitions）
func (a *BaseAgent) chatWithTools(ctx context.Context, msgs []Message, activeSkill *Skill) {
    for i := 0; i < budget; i++ {
        // 按当前 Skill 裁剪，非全量
        toolDefs := a.toolManager.GetToolDefinitionsFiltered(activeSkill.ToolRefs)
        resp, err := a.llmClient.ChatWithTools(ctx, msgs, toolDefs)
        ...
    }
}
// ToolManager 新增 Filtered 接口（原 GetToolDefinitions 是全集）
```

```Python
async def chat_with_tools(
    self, messages: list[Message], active_skill: Skill | None
) -> str:
    tool_refs = active_skill.tool_refs if active_skill else self.base_tool_refs
    # 循环外先裁剪一次；模型只能看到当前任务允许的工具 schema。
    visible_tools = self.tool_manager.definitions_for(tool_refs)
    for _ in range(self.max_iterations):
        response = await self.llm.chat_with_tools(messages, visible_tools)
        if not response.tool_calls:
            return response.text
        messages.extend(await self.execute_tool_calls(response.tool_calls))
    raise IterationLimitError
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **schema 按 Skill 裁剪** | ReAct 用 toolRefs 子集，非全集 | 真正收窄工具面（治工具数量陷阱） |
| **无 Skill 命中** | 退化到基础工具集 | 兜底（不是零工具） |
| **ToolManager 加 Filtered 接口** | 原全集接口保留，新增过滤版 | 开闭原则 |

### 5.5.5 Compaction 持久性预算（压缩不丢经验）

> 现状：会话记忆有压缩阈值（`ConversationMemory` 默认 30 条触发），但**没有 Skill 维度的预算**——压缩时可能把已注入的 Skill 内容一起压掉。扩展篇加 Skill 持久性预算。

```Plaintext
Compaction 持久性预算（借鉴 Claude Code）：
   上下文压缩时：
   · 已加载的 Skill【重新附加】到压缩后上下文（不丢）
   · 每个 Skill 保留前 5,000 Token
   · 所有 Skills 共享 25,000 Token 总预算
   · 预算超了 → 按相关性裁剪，只留最相关的

   接第 1 章（记忆压缩）：压缩历史的逻辑 + Skill 持久性预算，两层叠加
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **压缩重附加** | 已加载 Skill 压缩后重新注入 | 长会话不丢经验 |
| **单 Skill 预算** | 5000T/个 | 单个 Skill 不失控 |
| **总预算** | 25000T | Skill 不占满上下文 |
| **超预算裁剪** | 按相关性留 | 有上限的持久 |

### 5.5.6 自动沉淀（≥5 次工具调用，质量门禁，写回 etcd）

> 现状：Skill 只来自人工写文件 / etcd PUT，**无自动沉淀**。扩展篇借鉴 Hermes 加自动沉淀，但服务端要加**质量门禁**（防 garbage in 污染平台）。

```Plaintext
自动沉淀闭环（服务端版，带门禁）：
   ReAct 完成 ──统计工具调用次数──▶
      ≥5 次 ──▶ 提炼执行路径为 Skill 草稿
                │
                ▼
      质量门禁（服务端独有）：
        · 成功率校验（任务真成功了？接错误处理章防假完成）
        · 重复性校验（和已有 Skill 重复？）
        · 可读性/结构校验
                │ 通过
                ▼
      写回 etcd ──DynamicConfigManager 广播──▶ 所有实例的下个 Session 可用
   → 形成"使用 → 沉淀 → 动态下发"闭环（复用现有 etcd 观察者模式）
```

```Java
// Java：自动沉淀钩子（chatWithTools 完成后）
public void maybeSediment(RequestContext ctx, ExecutionTrace trace) {
    if (trace.toolCallCount() < 5) return;              // Hermes 阈值
    Skill draft = traceExtractor.extract(ctx.userIntent(), trace);
    if (!qualityGate.check(draft, trace)) return;       // 服务端质量门禁
    draft.setProvenance(signedProvenance(ctx.tenantId())); // 来源签名
    etcd.put("/skills/" + draft.getId(), draft.toJson());  // 写回，广播
}
```

```Go
// Go：自动沉淀
func (s *SedimentHook) Maybe(ctx *RequestContext, trace ExecutionTrace) {
    if trace.ToolCallCount() < 5 { return }
    draft := s.extractor.Extract(ctx.UserIntent, trace)
    if !s.qualityGate.Check(draft, trace) { return }    // 质量门禁
    draft.Provenance = signedProvenance(ctx.TenantID)   // 来源签名
    s.etcd.Put("/skills/"+draft.ID, draft.JSON())       // 写回，广播
}
```

```Python
async def maybe_sediment(self, context: RequestContext, trace: ExecutionTrace) -> None:
    if trace.tool_call_count < 5:
        return
    draft = await self.extractor.extract(context.user_intent, trace)
    # 未通过成功率、去重和结构校验的草稿绝不写入共享配置。
    if not await self.quality_gate.check(draft, trace):
        return
    draft.provenance = self.sign_provenance(context.tenant_id)
    await self.etcd.put(f"/skills/{draft.id}", draft.model_dump_json())
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **≥5 次触发** | Hermes 启发式阈值 | 执行→经验闭环 |
| **质量门禁** | 成功/重复/可读性校验 | 防 garbage in（服务端独有） |
| **写回 etcd** | 复用现有观察者广播 | 天然跨实例传播（PLUR 等价） |
| **来源签名** | 沉淀的 Skill 也要签名 | 供应链治理 |

### 5.5.7 "释放"语义升级（注销通知 + 回收 + 清理）

> 现状：`unregisterSkill` 只是 `skills.remove(name)` 删 key——**不通知正在用该 Skill 的 Agent、不回收已注入工具、不清理已塞进 prompt 的内容**。对比 Agent 注销有完整 graceful 流程（关 MCP 客户端等），Skill 没有对应物。扩展篇补"释放"的完整语义。

```Plaintext
现状（只删 key） vs 扩展篇（完整释放）：
   现状：unregisterSkill(id) → skills.remove(id) → 完
         问题：Agent 还在用这个 Skill，工具还注入着，prompt 还含着

   扩展篇释放四步：
   ① 从注册表删除（已有）
   ② 通知正在使用的 Agent（SkillReleased 事件）
   ③ 回收该 Skill 注入的工具（从 Agent.toolSpecifications 移除）
   ④ 标记 prompt 中该 Skill 内容为失效（下个 Session 不再注入）
   → 释放是"语义"动作，不只是"数据结构"动作
```

```Java
// Java：释放语义升级
public void unregisterSkill(String skillId) {
    Skill removed = skills.remove(skillId);             // ① 删注册表（已有）
    if (removed != null) {
        for (Agent a : agentsUsing(skillId)) {
            a.onSkillReleased(removed);                  // ② 通知 + ③ 回收工具
        }
        promptInvalidator.markStale(skillId);            // ④ 标记 prompt 失效
    }
}
```

```Go
// Go：释放语义升级
func (m *SkillManager) Unregister(id string) {
    removed := m.deleteSkill(id)                         // ① 删注册表
    if removed != nil {
        for _, a := range m.agentsUsing(id) {
            a.OnSkillReleased(removed)                   // ②③ 通知+回收
        }
        m.promptInvalidator.MarkStale(id)                // ④ 标记失效
    }
}
```

```Python
async def unregister(self, skill_id: str) -> None:
    removed = self.skills.pop(skill_id, None)  # ① 从注册表移除
    if removed is None:
        return
    # ②③ 通知使用者，并回收该 Skill 动态注入的工具。
    await asyncio.gather(
        *(agent.on_skill_released(removed) for agent in self.agents_using(skill_id))
    )
    # ④ 使旧 Prompt 缓存失效；下一会话不再注入该 Skill。
    self.prompt_invalidator.mark_stale(skill_id)
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **通知使用方** | SkillReleased 事件 | 卸载不残留（运行时感知） |
| **回收工具** | 从 Agent schema 移除 | 工具面同步收窄 |
| **标记 prompt 失效** | 下个 Session 不注入 | 已注入内容有生命周期 |
| **语义 vs 数据** | 释放是语义动作 | 对齐 Agent 注销的 graceful |

### 5.5.8 声明式配置（etcd 动态 Skill 策略）

```YAML
# Skill 声明式配置（etcd 下发，多实例广播）
skill:
  progressiveDisclosure:
    enabled: true                # 渐进式披露三层
    nameLayerTokens: 100         # 第一层每 Skill
    contentLayerTokens: 5000     # 第二层上限
  compaction:
    perSkillBudget: 5000         # 单 Skill 持久预算
    totalBudget: 25000           # 总预算
  visibilityScoping: true        # 可见性裁剪（ReAct schema 按 Skill）
  autoSediment:
    enabled: true                # 自动沉淀
    toolCallThreshold: 5         # Hermes 阈值
    qualityGate: true            # 服务端质量门禁
    tenantScoped: true           # 按租户隔离（坏经验不跨租户）
```

> 和核心卖点的关系：Skill 全生命周期（加载/沉淀/释放）走 etcd 动态——**不重启就能加/改/删 Skill**，`DynamicConfigManager` 监听广播。这正是声明式核心的强项，扩展篇只是把"释放"和"沉淀"的反向通道接上，形成闭环。

### 5.5.9 文件清单

| **文件** | **改动** | **说明** |
| --- | --- | --- |
| `SkillEnhancer` | 渐进式披露三层（已有第二层，补第一/三层） | 按需加载 |
| `BaseAgent.chatWithTools` | schema 按 Skill 裁剪（非全集） | 可见性裁剪（打通关键） |
| `ToolManager` | 新增 `GetToolDefinitionsFiltered` 接口 | 可见性裁剪基础 |
| `ConversationMemory` | Skill 持久性预算（5000T/个，总 25000T） | 压缩不丢经验 |
| `SedimentHook`**（新增）** | ≥5 次触发 + 质量门禁 + 写回 etcd | 自动沉淀 |
| `QualityGate`**（新增）** | 成功/重复/可读性校验 | 防 garbage in（服务端） |
| `SkillManager.unregister` | 释放四步（通知/回收/标记） | 释放语义升级 |
| **etcd schema** | `skill.*` 策略 | 声明式动态 Skill |

---

## 5.6 面试答法

| **面试官问** | **标准答法** |
| --- | --- |
| **Skill 怎么加载的？** | 双源（文件 SKILL.md + etcd 动态）、匹配双策略（LLM 语义 + 关键词）。加载用渐进式披露三层：启动期只载 name+description（~100T/个），命中才载完整内容（<5000T），脚本不入上下文直接执行（0T）。 |
| **Skill/工具一多，上下文怎么管？** | 渐进式披露把"经验规模"和"使用成本"解耦——启动期固定只付名字层的钱。再加 Compaction 持久性预算（5000T/个，总 25000T），压缩时已加载 Skill 重新附加，超预算按相关性裁剪。 |
| **你说按需加载，模型真能只看到相关工具吗？** | 靠可见性裁剪——SkillEnhancer 按 Skill 的 toolRefs 过滤后，接进 ReAct 的 tool schema，模型每轮只看到 Skill 相关工具，不是全集。否则过滤只作用于 prompt 文本、schema 仍全集，工具数量陷阱（>5-10 拉低决策）照样中招。 |
| **Skill 不用了怎么"释放"？** | 释放是语义动作，不只是删注册表 key。四步：删注册表 → 通知正在使用的 Agent → 回收已注入工具 → 标记 prompt 内容失效。对比 Agent 注销的 graceful 流程，Skill 释放要对齐。 |
| **Skill 能自动生成吗？** | 能。≥5 次工具调用的复杂任务，自动提炼执行路径为 Skill 草稿，过质量门禁后写回 etcd，广播给所有实例——形成"使用→沉淀→下发"闭环（借鉴 Hermes）。 |
| **服务端和本机工具的 Skill 有什么不同？** | 三点：① 来源治理——Skill/MCP 来源签名+平台审核（防 ClawHub 类）；② 沉淀要质量门禁——garbage in 防护，服务端一个租户沉淀的坏经验不能污染全平台；③ 按租户隔离——私有/共享 Skill 分级。本机工具（Claude Code 等）自写自用，没这些要求。 |
| **自动沉淀的 Skill 质量怎么保证？** | 质量门禁三层：成功率校验（任务真成功了？接错误处理章防假完成，防自我欺骗沉淀错误经验）、重复性校验（和已有 Skill 去重）、可读性/结构校验。Hermes 的教训就是无质量评估会 garbage in。 |
| **渐进式披露三层分别多少 Token？** | 第一层 name+description ~100T/个；第二层完整内容 <5000T；第三层脚本 0T（不入上下文，直接执行）。200 个 Skill 启动期只付 2 万 Token（名字层），不是 100 万（全量预付）。 |

---

## 小结

1. **Skill = 经验从哪来 + 怎么不爆上下文**：三大 Agent 各押一种赌注（社区/个人/进化），加载的解是同一招——渐进式披露。

1. **渐进式披露三层**：100T 名字 / 5000T 内容 / 0T 脚本直执行，把经验规模和使用成本解耦。

1. **可见性裁剪是打通关键**：SkillEnhancer 的过滤必须接进 ReAct 的 schema，否则过滤是装饰性的（现状的真实脱节）。

1. **Compaction 持久性预算**：压缩不丢经验（5000T/个，总 25000T），超预算按相关性裁剪。

1. **自动沉淀带质量门禁**：≥5 次触发，服务端必须质量校验 + 来源签名 + 按租户隔离（防 garbage in 污染平台）。

1. **释放是语义动作**：通知 + 回收 + 标记，非只删 key；和声明式核心（etcd 动态）形成"加载→沉淀→释放"闭环。

> 下一章：工具沙盒——Skill 管"此刻该看见哪些能力"，沙盒管"**工具在哪里执行、爆炸半径多大**"（三层防线 + ExecutionBackend + 服务端容器语义）。
