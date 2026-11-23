# 第 9 章 · Gateway 架构：控制平面 + 过滤器链 + 层级 Session Key + fail-closed

> **为什么有这章**：原项目 Gateway 的过滤器链骨架扎实（统一接口 + order 责任链，Auth/Security/RateLimit）——已有；但只有 3 层、session key 是平面的（多租户没"唯一地址"）、fail-closed 不严（Filter 可关、路由 fallback 放行、限流失败放行）。这章补**层级 session key（agent:tenant:channel:peer）+ bindings 路由 + 严格 fail-closed**；对应学员反馈"agent 网关"。

## 这篇讲什么

Gateway 要解决的问题是：**请求怎么进来、怎么路由到对的 Agent、会话怎么隔离、出错放行还是拒绝**——别让多渠道消息找不到入口，也别让一个租户的请求绕过鉴权碰到别人的 session。

为什么服务端要单拎出来讲：本机的 Claude Code 压根没有 Gateway——它是 CLI，关掉终端就结束；服务端平台的 Gateway 是控制平面，多渠道接入、多租户隔离、多 Agent 调度、7×24 在线全压在它身上。照搬本机工具，连入口都没有。这是服务端和本机最显眼的差异之一。

**核心模型：控制平面 + 过滤器链 + 层级 session key + fail-closed。** Gateway 是请求从进到 Agent 的一条流水线，每层挡一类问题：

```Plaintext
Gateway = 控制平面（请求从进到 Agent 的流水线）：

  外部消息(HTTP/IM/WebSocket)
     → ① Channel Bridge       多渠道适配归一化
     → ② 过滤器链              Auth → Security → RateLimit（强制启用，不可绕过）
     → ③ Session Resolution   层级 key = agent:tenant:channel:peer
     → ④ bindings 路由         按渠道/用户/团队 → agentId（不靠 LLM 路由）
     → Agent Runtime
  出错 fail-closed：拒绝，不 fallback 放行
```

**三个关键认知（想通这三个，这章就懂了）：**

- **Gateway 是服务端独有的大模块**：本机 Claude Code 是 CLI，没有 Gateway；服务端少了它，多渠道接入、多租户隔离、多 Agent 调度就没人管。这是"服务端平台 vs 本机工具"最本质的差异之一，不能照搬本机做法。

- **多租户隔离的标答是层级 session key**：`agent:tenant:channel:peer` 给每个对话一个唯一地址，一个层级化的 key 同时解决路由和隔离——Slack DM 与 Telegram 群组天然不共享 session 历史。这比平面的 session key（现状）干净得多。

- **出错要 fail-closed，不是 fail-open**：OpenClaw 的 default-open 是反面教材（13.5 万暴露实例、63% 无认证）；现状里 Filter 可 enabled=false 绕过、agentId fallback 到"第一个可用 Agent"、限流失败时放行，都是 fail-open 兜底。多租户里不确定即拒绝，不能放行。

> 这是这章的骨架。过滤器链怎么强化、层级 session key 怎么生成、bindings 路由怎么扩 DomainRouter，往下看 §9.2 起。**细节是用来查的，不用全记**；先把"控制平面 + 层级 key + fail-closed"这三件事想清楚。

---

## 9.1 痛点：Gateway 决定隔离、路由、安全姿态

学员反馈方向：

> "agent 网关。"

面试官的连环追问：

```Plaintext
你的 Agent 平台，请求从哪进来？
   └─ 多个渠道（HTTP / IM 平台 / WebSocket）怎么接入？
       └─ 一个请求怎么路由到正确的 Agent？（路由决策方是谁）
           └─ 不同用户/渠道的会话怎么隔离？（session 怎么标识）
               └─ 鉴权 / 限流 / 安全检查，顺序是什么？能被绕过吗？
                   └─ 出错时默认放行还是拒绝？（fail-open 还是 fail-closed）
```

**现状诊断：过滤器链扎实，但只有 3 层；session key 平面；fail-closed 不严**

基于真实代码核对（Java `com.bitguide.gateway` + Go `pkg/gateway`）：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

> 框成设计：过滤器链骨架（统一接口 + order 责任链）做得扎实，是亮点。扩展篇把它从"3 层 + 平面 session + fail-open 兜底"升级到"5 层 + 层级 session key + 严格 fail-closed"——这是从"能跑的网关"到"生产级控制平面"的跨越。学员面试直接说"我们的 Gateway 是控制平面，过滤器链 + 层级 session key + fail-closed"。

---

## 9.2 三大 Agent 的 Gateway（重点：讲透）

### 9.2.1 核心问题：Gateway 是核心还是入口

三大 Agent 对 Gateway 的定位完全相反，这是它们部署哲学的缩影：

```Plaintext
Gateway 定位（三大取舍）：
   OpenClaw    ──Gateway 即操作系统──▶ 核心产品 / 控制平面 / 长驻进程
   Claude Code ──无 Gateway──▶ CLI 工具（终端关了就结束，事后补远程层）
   Hermes      ──薄适配层──▶ Gateway 可选（没它也是完整 Agent）

   三大取舍：
   ① Gateway 是核心还是入口（越重越集中、能力越强）
   ② 永远在线还是按需启动（VPS 成本+暴露面 vs 失去主动性）
   ③ 默认开放还是默认拒绝
```

### 9.2.2 OpenClaw —— Gateway 即操作系统（5 层流水线，最完整）

OpenClaw 的 Gateway 是长期运行的控制平面——路由、鉴权、会话、多 Agent 调度全在里面。它的 5 层流水线是 Gateway 设计的教科书：

```Plaintext
OpenClaw 5 层消息流水线：
   外部消息(WhatsApp/Telegram/Slack/Discord…)
        │
        ▼
   ① Channel Bridge      各平台适配器（50+ 渠道）
        │
        ▼
   ② Session Resolution  按 dmScope 规则确定 session key
        │
        ▼
   ③ Command Queue       串行化（防工具竞争）
        │
        ▼
   ④ Agent Runtime       LLM 推理 + 工具执行
        │
        ▼
   ⑤ 结果流回原渠道

   bindings 路由：match channel/teamId/peer.kind → agentId
   Session key 层级：agent:main:channel:peer（给每个对话唯一地址）
   主动触发：Heartbeat(30min) + Cron（隔离新 session 执行）
   安全默认：default-open（反面！13.5 万暴露实例，63% 无认证）
```

**Session key 层级结构**（多租户隔离的标答，面试必答）：

```Plaintext
agent:main:channel:peer
  └─ 给每个对话一个唯一地址
  └─ 天然实现上下文隔离（Slack DM 与 Telegram 群组不共享 session 历史）
  └─ 这就是多租户隔离的"标准答案"——一个层级化的 key 解决路由+隔离
```

> 教学点：OpenClaw 的 5 层流水线 + 层级 session key 是 Gateway 设计的黄金参考。但它的 default-open 是反面教材——机制全有，默认值致命。

### 9.2.3 Claude Code —— 无 Gateway（CLI，事后补远程层）

Claude Code 哲学是"Agent 是你工作时用的工具，不是替你工作的员工"——根本没 Gateway：

```Plaintext
Claude Code：CLI 工具，不是守护进程
   打开终端运行 claude → 关掉终端即结束
   无监听端口、无消息路由层、无永远在线进程

   三种远程控制层（Anthropic 2026 Q1 事后补，都依赖本地在线）：
   ① Remote Control  人主动连进去（远程控制终端）——本地机器+终端必须开着
   ② Dispatch        人发任务 Agent 异步完成（同步→异步任务队列）——机器要开
   ③ Channels        外部系统推消息进来（就是 MCP 服务器）——机器要开

   共同局限：都依赖本地机器在线，电脑关机一切停止
```

> 教学点：Claude Code 没有 Gateway，因为它是本机 dev-tool。这正说明——**Gateway 是服务端平台才需要的东西**。我们（服务端）照搬 Claude Code 的话连入口都没有。

### 9.2.4 Hermes —— 薄适配层（三层职责，fail-closed，ACP）

Hermes 把 Gateway 定位成可选的薄适配层——执行循环才是核心，没 Gateway 也是完整 Agent：

```Plaintext
Hermes Gateway：薄适配层
   消息路径（三层职责不重叠）：
      平台消息 → Platform Adapter(归一化格式) → 授权检查+session key → AIAgent.run_conversation()
      ① 适配器只管格式转换
      ② 核心层只管鉴权和路由
      ③ AIAgent 只管执行
   → 加新平台只加适配器，不动其他（18 个平台适配器，架构稳定）

   单 Gateway 单 Agent：需要多个 Agent → 启动多个 Gateway 实例（成本显式）
   fail-closed：默认拒绝（与 OpenClaw default-open 相反）
   ACP：接编辑器（VS Code 实时传文件/光标/选区）

   演进方向：从"消息桥接层"变为"上下文聚合层"
            横向接消息渠道，纵向接开发工具链
```

### 9.2.5 横向对比 + 关键设计点

| **维度** | **OpenClaw** | **Claude Code** | **Hermes** |
| --- | --- | --- | --- |
| **Gateway 定位** | 核心/控制平面 | 无（事后补） | 薄适配层/可选 |
| **运行模式** | 永远在线（VPS 7×24） | 按需（绑本地机器） | 永远在线（服务器） |
| **流水线层数** | 5 层 | 无 | 3 层 |
| **多 Agent 调度** | 单进程 bindings | 无 | 单 Gateway 单 Agent（多实例） |
| **Session key** | 层级 `agent:main:channel:peer` | 无 | 解析生成 |
| **主动触发** | Heartbeat + Cron | 无 | 未承担 |
| **安全默认** | default-open（反面） | — | fail-closed |

---

## 9.3 选型：服务端多租户语境下的 Gateway

> 关键差异：本机 dev-tool（Claude Code）**根本没有 Gateway**（CLI，关终端就结束）；**服务端平台的 Gateway 是控制平面**——多渠道接入、多租户隔离、多 Agent 调度、7×24 在线全在它身上。这是服务端和本机最显眼的差异之一。

| **维度** | **本机Claude Code** | **服务端AgentForge** |
| --- | --- | --- |
| **Gateway** | 无 CLI 关终端即结束 | 控制平面 7×24在线 |
| **多渠道** | 无 | HTTP/IM/WebSocket接入 |
| **隔离** | 无 | 层级session key |

**服务端 Gateway 的独有要求**（面试可答）：

```Plaintext
多渠道接入   HTTP/IM/WebSocket 统一接入（Channel Bridge）
多租户隔离   层级 session key（agent:tenant:channel:peer）
确定性路由   bindings（按渠道/用户/团队 → agentId），不靠 LLM 路由
严格 fail-closed  出错拒绝，不 fallback 放行（接 ch3 权限）
主动触发     Heartbeat/Cron（接 ch8 主动 Agent）
```

**选型结论**：

```Plaintext
我们的 Gateway = 过滤器链（已有）+ 层级 session key + bindings 路由 + fail-closed

  入口：过滤器链（Auth → Security → RateLimit，已有，强化为不可绕过）
  流水线：补 Session Resolution 层（3层→接近5层）
  隔离：层级 session key（agent:tenant:channel:peer）
  路由：bindings（扩 DomainRouter）
  姿态：严格 fail-closed（修 enabled 绕过 / fallback 放行）
  渠道：Channel Bridge（架构方向）
  主动：Heartbeat/Cron（接 ch8）
```

---

## 9.4 适配：本机 → 服务端多租户

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| **三大 Agent（本机为主）** | **AgentForge（服务端多租户）** | **搬/改/弃** |
| --- | --- | --- |
| **5 层流水线（OpenClaw）** | 已有 3 层，补 Session Resolution | **搬+补** |
| **层级 session key（OpenClaw）** | 直接用（多租户标答） | **搬** |
| **bindings 路由（OpenClaw）** | 扩 DomainRouter | **搬** |
| **三层职责不重叠（Hermes）** | 直接用（加渠道只加适配器） | **搬** |
| **fail-closed（Hermes）** | 直接用（修 fail-open 兜底） | **搬** |
| **上下文聚合层（Hermes）** | 架构方向 | **参考** |
| **Heartbeat/Cron（OpenClaw）** | 接 ch8 主动 Agent | **搬** |
| **无 Gateway（Claude Code）** | 弃（服务端必须有） | **弃** |

---

## 9.5 落地：AgentForge 的 Gateway

### 9.5.1 总架构

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

### 9.5.2 现状诚实呈现：过滤器链扎实，但 3 层 + 平面 session + fail-open 兜底

> 现状有三个值得讲的点（设计骨架好，但缺生产级形态）：

```Plaintext
现状①【过滤器链扎实】（亮点）：
   统一 GatewayFilter 接口 + order 责任链
   Java：AuthFilter(100) → SecurityFilter(150) → RateLimitFilter(200)
   Go：  Auth(10) → Security(20) → RateLimit(30)
   SecurityFilter 还聚合多个 validator（InputLength/PromptInjection/SensitiveWord）
   → 这是责任链模式的干净实现，对标 OpenClaw 流水线的过滤器段

现状②【只有 3 层，session key 平面】（待补）：
   现状流水线：过滤器链 → 路由 → Lane → Agent（3-4 层）
   对比 OpenClaw 5 层：缺 Channel Bridge（多渠道）+ Session Resolution（身份→session）
   session key：Java 是平面 sessionId 字符串；Go 仅 user:session 两层
   → 没有 agent:tenant:channel:peer 层级 = 多租户隔离没有"唯一地址"

现状③【fail-closed 不严】（安全缺口）：
   · 异常路径 fail-closed（try/catch 转 error，不上抛）✅
   · 但 Filter 可 enabled=false 绕过（authEnabled=false 时无 token 也放行）❌ fail-open
   · Go agentId fallback 到"第一个可用 Agent"（未指定就自动路由）❌ fail-open
   · Go 分布式限流检查失败时放行（err != nil → return true）❌ fail-open
   · （细节）SecurityFilter order=150 实际在 RateLimit(200) 之前执行，
     与代码注释"在限流之后"不符——注释 bug，教学可点出
```

> 框成设计：过滤器链骨架（统一接口 + order 责任链）是干净的，扩展篇做三件事——补 Session Resolution 层、把 session key 升级成层级、把 fail-open 兜底收紧成 fail-closed。

### 9.5.3 过滤器链（已有，强化为不可绕过，关键实现示例）

```Java
// Java：过滤器链已扎实（GatewayFilter 接口 + order），扩展篇强化"不可绕过"
public class DefaultFilterChain {
    // 现状：每个 Filter 有 enabled 开关，关闭就跳过（fail-open 风险）
    // 扩展篇：安全关键 Filter（Auth/Security）强制启用，不允许关闭
    if (filter instanceof SecurityCritical && !filter.isEnabled())
        throw new IllegalStateException("安全过滤器不可关闭: " + filter.getName());
}
```

```Go
// Go：安全关键 Filter 强制启用
func (c *FilterChain) Process(ctx *RequestContext) *GatewayResponse {
    for _, f := range c.filters {
        if isSecurityCritical(f) && !f.enabled {
            return reject("安全过滤器不可关闭: " + f.GetName()) // 不再跳过
        }
        if resp := f.Process(ctx); resp != nil { return resp }
    }
    return nil
}
```

```Python
async def process(self, context: RequestContext) -> GatewayResponse | None:
    for gateway_filter in self.filters:
        if gateway_filter.security_critical and not gateway_filter.enabled:
            # 安全过滤器被关闭属于配置错误，必须拒绝而不是静默跳过。
            return GatewayResponse.reject(
                f"安全过滤器不可关闭: {gateway_filter.name}"
            )
        if response := await gateway_filter.process(context):
            return response
    return None
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **统一接口 + order** | GatewayFilter + getOrder | 责任链，顺序可控 |
| **安全 Filter 强制启用** | 不允许 enabled=false | 修 fail-open 绕过 |
| **顺序固定** | Auth→Security→RateLimit | 先认证后限流后路由 |

### 9.5.4 层级 Session Key（扩展篇补，多租户标答，关键实现示例）

> 这是本章最重要的补点——OpenClaw 的 `agent:main:channel:peer` 是多租户隔离的标答。我们把平面 sessionId 升级成层级 key。

```Java
// Java：层级 session key（agent:tenant:channel:peer）
public class SessionResolver {
    // 从请求身份解析出层级 session key（而非平面 sessionId）
    public String resolve(RequestContext ctx) {
        return String.join(":",
            "agent", ctx.getAgentId(),
            ctx.getTenantId(),          // 租户（接第 2 章）
            ctx.getChannel(),           // 渠道（slack/telegram/http）
            ctx.getPeerId()             // 对话对端（用户/群组）
        );
        // → 每个对话唯一地址，天然隔离（A 租户 Slack DM 与 B 租户 Telegram 群不共享）
    }
}
```

```Go
// Go：层级 session key
func ResolveSessionKey(ctx *RequestContext) string {
    return strings.Join([]string{
        "agent", ctx.AgentID,
        ctx.TenantID,   // 租户
        ctx.Channel,    // 渠道
        ctx.PeerID,     // 对端
    }, ":")
}
```

```Python
def resolve_session_key(context: RequestContext) -> str:
    # 每个维度都来自已认证上下文，不能直接信任请求体中的任意值。
    return ":".join((
        "agent", context.agent_id, context.tenant_id,
        context.channel, context.peer_id,
    ))
```

```Plaintext
层级 session key 的价值：
   agent:tenantA:slack:user123   ──A 租户 Slack 私聊──▶ 独立 session
   agent:tenantA:telegram:group1 ──A 租户 Telegram 群──▶ 独立 session
   agent:tenantB:slack:user456   ──B 租户 Slack 私聊──▶ 独立 session
   → 一个层级化 key 同时解决【路由 + 隔离】，不用额外的隔离逻辑
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **层级 key** | agent:tenant:channel:peer | 多租户隔离标答 |
| **唯一地址** | 每对话一个 | 天然隔离，不共享 |
| **接第 2 章** | tenantId 贯穿 | 多租户隔离闭环 |

### 9.5.5 bindings 路由（扩 DomainRouter，关键实现示例）

> 现状 Java 有 DomainRouter（domain→agentId 硬编码：parenting/stock/finance），Go 无 router 类（fallback first-available）。扩展篇升级为 bindings 式（按渠道/用户/团队多维路由）。

```Java
// Java：bindings 路由（match channel/teamId/peer → agentId，扩 DomainRouter）
public class BindingRouter {
    public String route(RequestContext ctx) {
        // 多维匹配（确定性，不靠 LLM 路由）
        for (Binding b : bindings) {
            if (b.matches(ctx.getChannel(), ctx.getTeamId(), ctx.getPeerKind()))
                return b.getAgentId();
        }
        return defaultAgent;   // 无 fallback 放行到随机 Agent（修 fail-open）
    }
}
```

```Go
// Go：bindings 路由（替代 first-available fallback）
func (r *BindingRouter) Route(ctx *RequestContext) (string, error) {
    for _, b := range r.bindings {
        if b.Match(ctx.Channel, ctx.TeamID, ctx.PeerKind) {
            return b.AgentID, nil
        }
    }
    return "", ErrNoBinding // 不 fallback 到随机 Agent（修 fail-open）
}
```

```Python
def route(self, context: RequestContext) -> str:
    for binding in self.bindings:
        if binding.matches(context.channel, context.team_id, context.peer_kind):
            return binding.agent_id
    # 没有确定性绑定就报错，不能退化为“随便找一个可用 Agent”。
    raise NoBindingError(context.channel, context.team_id, context.peer_kind)
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **多维 bindings** | channel/teamId/peer → agentId | 确定性路由 |
| **不靠 LLM 路由** | 程序配置决定 | 可预测、可控 |
| **无匹配拒绝** | 不 fallback 随机 | 修 fail-open |

### 9.5.6 严格 fail-closed（修三处 fail-open）

```Plaintext
三处 fail-open 修复：
   ① Filter enabled 绕过 → 安全关键 Filter 强制启用（见 9.5.3）
   ② agentId fallback 到随机 Agent → 无匹配拒绝（见 9.5.5）
   ③ 限流检查失败放行 → 失败拒绝
```

```Java
// Java：限流检查失败拒绝（修 distributed.go 的 err→放行）
if (!rateLimitManager.check(ctx)) return GatewayResponse.error("rate limited");
// 扩展篇：check 内部异常也拒绝，不 return true 兜底
```

```Go
// Go：限流失败拒绝（修 distributed.go:193 的 if err!=nil { return true }）
allowed, err := f.checkRateLimit(ctx)
if err != nil { return reject("rate limit check failed") } // 不放行
if !allowed  { return reject("rate limited") }
```

```Python
try:
    allowed = await rate_limit_filter.check(context)
except Exception as exc:
    # 分布式限流器异常时默认拒绝，并保留可观测错误；不能把异常当作允许。
    logger.exception("rate limit check failed", exc_info=exc)
    return GatewayResponse.reject("rate limit unavailable")

if not allowed:
    return GatewayResponse.reject("rate limited")
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **三处修复** | enabled/fallback/限流失败 | 收紧 fail-open |
| **不确定即拒绝** | fail-closed | 多租户安全姿态 |

### 9.5.7 多渠道适配器（Channel Bridge，架构方向）

> 现状无 Channel Bridge（无 WebSocket/HTTP Controller，接入协议上层自己实现）。扩展篇给出架构方向——参考 Hermes 三层职责（适配器管格式 / 核心管鉴权路由 / Agent 管执行），加渠道只加适配器。

```Plaintext
Channel Bridge（架构方向）：
   各平台适配器（HTTP/Slack/Telegram/WebSocket）──归一化消息格式──▶
   核心层只认统一格式，不关心来源
   → 加新渠道只加适配器，不动核心（Hermes 18 平台架构稳定的原因）
   → 演进方向：从消息桥接层 → 上下文聚合层（横向渠道 + 纵向工具链）
```

### 9.5.8 声明式配置（etcd 动态 Gateway 策略）

```YAML
# Gateway 声明式配置（etcd 下发）
gateway:
  filters:
    chain: [auth, security, rateLimit]
    securityCritical: [auth, security]   # 不可关闭
    order: { auth: 100, security: 150, rateLimit: 200 }
  session:
    keyPattern: "agent:tenant:channel:peer"   # 层级 key
  routing:
    bindings:
      - { match: { channel: slack, team: core }, agent: core_agent }
      - { match: { channel: http, peer: vip },  agent: vip_agent }
    noMatch: reject                          # 不 fallback（fail-closed）
  failClosed: true                           # 限流/检查失败拒绝
  channels: [http, slack, telegram]          # 接入渠道（架构方向）
```

> 和核心卖点的关系：Gateway 策略（过滤器顺序/bindings/session 模式）走 etcd 动态下发——**不重启就能加路由绑定、调过滤器、开新渠道**。`DynamicConfigManager` 广播，落到 FilterChain/Router。

### 9.5.9 文件清单

| **文件** | **改动** | **说明** |
| --- | --- | --- |
| `GatewayFilter`** / FilterChain（已有）** | 强化安全 Filter 不可关 | 过滤器链 |
| `SessionResolver`**（新增）** | 层级 session key | 多租户隔离标答 |
| `BindingRouter`**（扩 DomainRouter）** | bindings 多维路由 | 确定性路由 |
| **限流/路由兜底** | 修三处 fail-open | 严格 fail-closed |
| `ChannelBridge`**（新增，架构方向）** | 多渠道适配器 | HTTP/IM/WS 接入 |
| `scheduler.tick`**（接 ch8）** | Heartbeat/Cron | 主动触发 |
| **etcd schema** | `gateway.*` 策略 | 声明式动态网关 |

---

## 9.6 面试答法

| **面试官问** | **标准答法** |
| --- | --- |
| **你们平台请求从哪进来？** | Gateway 控制平面——多渠道（HTTP/IM/WS）经 Channel Bridge 归一化 → 过滤器链（Auth→Security→RateLimit）→ Session Resolution（层级 key）→ bindings 路由 → Lane 串行化 → Agent。Gateway 是服务端才有的，本机工具（Claude Code 是 CLI）根本没有。 |
| **多个用户/渠道的会话怎么隔离？** | 层级 session key：`agent:tenant:channel:peer`，给每个对话一个唯一地址，天然隔离（A 租户 Slack DM 与 B 租户 Telegram 群不共享 session）。一个层级化 key 同时解决路由 + 隔离。这是 OpenClaw 的设计，多租户隔离的标答。 |
| **请求怎么路由到正确的 Agent？** | bindings 确定性路由——按渠道/团队/对端匹配到 agentId，配置决定（可预测、人可控）。不靠 LLM 自主路由（那个不可控）。无匹配直接拒绝，不 fallback 到随机 Agent。 |
| **鉴权/限流/安全检查顺序？能被绕过吗？** | 过滤器链固定顺序 Auth(100)→Security(150)→RateLimit(200)，统一 GatewayFilter 接口 + order 责任链。安全关键 Filter（Auth/Security）强制启用、不允许 enabled=false 绕过。 |
| **出错时放行还是拒绝？** | 严格 fail-closed——限流检查失败、路由无匹配、Filter 被关，一律拒绝，不 fallback 放行。多租户平台不能 fail-open 兜底（对比 OpenClaw default-open 导致 13.5 万暴露实例的反面）。 |
| **支持哪些渠道接入？** | Channel Bridge 适配器模式——HTTP/IM/WebSocket 各一个适配器归一化消息格式，核心层只认统一格式。加新渠道只加适配器，不动核心（参考 Hermes 18 平台架构稳定）。演进方向是上下文聚合层。 |
| **Agent 能不能主动干活？** | 能，Gateway 主循环挂 scheduler.tick，Heartbeat（30 分钟周期自检）+ Cron（定时新 session 执行）。但 Gateway 本身是被动触发为主，主动是扩展篇补的（接第 8 章）。 |
| **服务端 Gateway 和本机工具差在哪？** | 本机工具（Claude Code）根本没 Gateway——它是 CLI，关终端就结束，所有"远程能力"都是事后补丁且依赖本地在线。服务端 Gateway 是控制平面：7×24 在线、多渠道、多租户隔离、多 Agent 调度、主动触发。照搬本机工具连入口都没有。 |

---

## 小结

1. **Gateway 是服务端独有的大模块**：本机 dev-tool（Claude Code）根本没有，服务端平台它是控制平面。

1. **过滤器链骨架扎实**：统一接口 + order 责任链（已有，亮点），扩展篇强化为安全 Filter 不可绕过。

1. **层级 session key 是多租户标答**：`agent:tenant:channel:peer`，一个 key 解决路由 + 隔离。

1. **bindings 确定性路由**：按渠道/用户/团队配置，不靠 LLM 自主路由。

1. **严格 fail-closed**：修三处 fail-open（Filter 可关 / fallback 随机 / 限流失败放行）。

1. **5 层流水线**：Channel Bridge → 过滤器链 → Session Resolution → 路由 → Lane → Agent（对标 OpenClaw）。

> 下一章：错误处理与容错——Gateway 管"请求怎么进来、怎么隔离"，容错管"**进来之后调用失败怎么办**"（错误分类 + 重试 + 熔断 + 降级 + 防假完成）。
