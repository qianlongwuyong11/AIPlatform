# 第 11 章 · MCP 集成与工具协议：MCP vs CLI + defer_loading + 凭证隔离 + 统一注册表

> **为什么有这章**：原项目 MCP 已有亮点——统一注册表（内置 + MCP 同一套，McpTool implements Tool）+ 双传输（stdio/http）完整；但全量加载（连接即拉全部 schema，多 server 爆上下文）、凭证全透传（MCP 子进程继承主机全部环境变量，Go 端 env 传 nil 是个 bug，主机密钥会泄给第三方 MCP）。这章补**defer_loading 按需加载 + 凭证白名单隔离**；对应学员反馈"MCP 集成"。

## 这篇讲什么

MCP 集成要解决的问题是：**外部能力（GitHub / 数据库 / SaaS）怎么标准化接进来、工具 schema 怎么不把上下文撑爆、MCP 子进程跑在服务器上凭证怎么不泄**——接得进来，还得接得安全、接得省。

为什么服务端要单拎出来讲：本机的 Claude Code 这类工具，MCP 子进程跑在自己机器上，凭证全透传自己承担，接几个工具也无所谓；我们是多租户共用服务器，MCP server 多是第三方 / 社区不可信代码，主机的 API Key / SSH 密钥一旦默认透传出去就是多租户灾难，而且多用户 OAuth 场景下身份管理也得靠 MCP。所以"按需加载"和"凭证隔离"在服务端是硬要求，不是优化项。

**核心模型：四件事，一件不能漏。** 外部能力从接入到运行依次定四件事：

```Plaintext
外部能力 → ①选型(MCP vs CLI) → ②统一注册表 → ③defer_loading → ④凭证白名单隔离 → Agent
           SaaS/OAuth走MCP       内置+MCP同一套  按需加载schema     不继承主机env
           本地高频走CLI          (已有,亮点)     Token税按需付       防密钥泄露
```

**三个关键认知（想通这三个，这章就懂了）：**

- **统一注册表是已有亮点，不是缺口**：内置工具和 MCP 工具共用同一个 ToolManager map、同一套权限门控和审计（Java `McpTool implements Tool` / Go `McpToolAdapter`）——对标 Claude Code "内外无别"。这一层已经做对，本章不用重做，只往上加 defer_loading 和凭证隔离。

- **不按需加载，MCP 就是上下文杀手**：GitHub 一个 MCP server 暴露 43 个工具，4 个 server（GitHub + 数据库 + Graph + Jira）光 schema 就吃掉 150,000+ Token。defer_loading 把"全量预付"改成"调用时才注入"，tool search 再做语义发现——这俩是接多个 MCP server 的前提。

- **凭证必须白名单注入，不能继承父进程全部 env**：现状是 MCP 子进程继承主机全部环境变量（Go `client.go` env 传 nil 是 bug），主机的 API Key 会泄给不可信的第三方 server。Hermes 的做法是默认不传、按 Skill 显式声明白名单——服务端多租户语境下这是必修。

> 这是这章的骨架。MCP 协议握手、defer_loading 实现、凭证白名单怎么配，往下看 §11.2 起。**细节是用来查的，不用全记**；先把上面这个四件事的模型和三个认知想清楚。

---

## 11.1 痛点：外部能力怎么接、schema 怎么不爆、凭证怎么不泄

学员反馈方向：

> "MCP 集成。"

面试官的连环追问：

```Plaintext
你们的 Agent 怎么接外部工具（GitHub / 数据库 / SaaS）？
   └─ 用 MCP 还是 CLI？怎么选？
       └─ 接多个 MCP 服务器，工具 schema 会不会撑爆上下文？
           └─ 接了 MCP，工具权限怎么管？（和内置工具一样吗）
               └─ MCP 子进程跑在服务器上，主机的 API Key / 密钥会不会泄露给它？
                   └─ MCP 工具失败/断连怎么办？
```

**现状诊断：统一注册表是亮点；全量加载 + 凭证全透传是缺口**

基于真实代码核对（Java `com.bitguide.mcp` + Go `pkg/mcp` + `mcp-servers/`）：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

> 框成设计：统一注册表（内置+MCP 同一套注册/权限路径）是亮点，对标 Claude Code 统一门控；双传输 + 教学示例也完整。扩展篇补两件：① defer_loading（按需加载 schema，接第 5 章）；② 凭证隔离（MCP 子进程不能继承主机全部 env，多租户安全必修）。学员面试直接说"我们 MCP 统一注册表 + defer_loading + 凭证隔离"。

---

## 11.2 三大 Agent 的 MCP / CLI（重点：讲透）

### 11.2.1 核心问题：MCP vs CLI 怎么选

MCP 不是唯一的外部能力接入方式——CLI 也是。三大 Agent 对两者的分工有共识：

```Plaintext
MCP vs CLI 场景分工（行业共识）：
   CLI 适合：模型训练已见过（git/gh/aws/docker）、本地执行无需多用户身份、
            高频轻量操作、批量循环（150 个 API 请求写 for 循环，MCP 要调 150 次）
   MCP 适合：无 CLI 的 SaaS（Salesforce/Workday）、需要 OAuth 多用户身份、
            需要审计日志和权限隔离的合规场景、工具发现

   Token 成本基线（反面警示）：
   GitHub MCP 单服务器暴露 43 个工具
   4 个 MCP 服务器（GitHub+数据库+Graph+Jira）光 schema 吃掉 150,000+ Token
   → 不按需加载，MCP 就是上下文杀手
```

### 11.2.2 OpenClaw —— MCP 生态继承（最大规模，无 defer）

```Plaintext
OpenClaw：MCP 作为主要扩展路径（生态继承，不自己造轮子）
   配置：openclaw.json 的 mcpServers 段，连哪个服务器用哪个工具
   规模：外部 5,800+ 个 MCP 服务器可接入
   MCPorter：把 MCP 服务器转换成 CLI 工具的转换器
   短板：未做按需加载 → 工具 schema 随服务器数【线性注入】上下文
   经验阈值：接入 >5-6 个 MCP 服务器后，上下文压力明显
   CLI：通过 Skills 封装命令行，但无 MCP 那样的统一发现机制
```

### 11.2.3 Claude Code —— 三层分工 + defer_loading + 统一注册表（范本）

Claude Code 是 MCP 工程化的标杆——三层分工、按需加载、统一门控：

```Plaintext
Claude Code 三层分工：
   CLI（默认执行）+ MCP（结构化扩展层）+ Skills（统一接口层）
   · CLI 走 Shell（文件/代码/Git 操作）
   · SaaS 集成走 MCP
   · Skills 让 Agent 不感知底层是 CLI 还是 MCP

   三个关键机制：
   ① defer_loading: true
      Session 启动只载工具【名称】，完整 schema 调用时才注入
      → MCP 的"Token 税"从全量预付变按需支付
   ② tool search
      语义搜索找相关工具，不遍历整个列表
      → 避免 N 个工具 schema 全量进上下文
   ③ 统一工具注册表
      内置工具与 MCP 工具【共用同一套注册表】
      统一权限门控、统一 schema 验证，内外无别
      （Computer Use 都走 @ant/computer-use-mcp 标准 MCP，非定制管道）
```

> 教学点：Claude Code 给出 MCP 工程化三件套——defer_loading（按需付）+ tool search（语义发现）+ 统一注册表（内外无别）。我们的统一注册表已经对标了第三件。

### 11.2.4 Hermes —— 双向参与 + 凭证隔离 + mcp serve（安全/互操作范本）

Hermes 的 MCP 最有特色的是**双向参与**和**凭证隔离**：

```Plaintext
Hermes：MCP 双向参与（既是客户端，也能当服务器）
   ① 客户端：连外部 MCP 服务器用工具
   ② mcp serve：把会话历史和记忆作为 MCP 资源【暴露给外部】
      → Claude Desktop / VS Code / Cursor 可通过 MCP 查 Hermes 历史
      → 记忆可被其他 AI 工具复用（跨 Agent 互操作）
   ③ ACP：与编辑器双向通信（传当前打开的文件 + 光标位置）

   凭证隔离（关键安全设计）：
   主机的敏感环境变量【默认不传入】MCP 子进程
   某服务器需要的变量必须在对应 Skill 里【显式声明】（白名单式注入）
   → 防止主机 API Key / SSH 密钥泄露给不可信的 MCP server
```

> 教学点：Hermes 的凭证白名单隔离是服务端必修——MCP server（尤其第三方/社区）是不可信代码，主机的密钥绝不能默认透传。这正是我们当前的缺口。

### 11.2.5 横向对比 + 关键设计点

| **维度** | **OpenClaw** | **Claude Code** | **Hermes** |
| --- | --- | --- | --- |
| **MCP 定位** | 主要扩展路径 | 结构化扩展层（与 CLI 并行） | 双向参与（客户端+服务器） |
| **按需加载** | 无（线性膨胀） | defer_loading + tool search | 未强调（侧重点凭证隔离） |
| **统一抽象** | Skills（部分） | Skills 统一 CLI+MCP | ACP（编辑器双向） |
| **凭证隔离** | 未强调 | 统一权限门控 | **白名单式注入**（默认不透传） |
| **生态规模** | 最大（5,800+） | 中（defer 缓解） | 特色在记忆外放 |
| **独特能力** | MCPorter（MCP→CLI） | Computer Use 走 MCP | mcp serve 记忆外放 |

---

## 11.3 选型：服务端多租户语境下的 MCP

> 关键差异：本机单用户，**MCP 子进程跑在自己机器上，凭证全透传自己承担**；**服务端多租户，MCP 子进程跑在共享服务器上**——主机的 API Key / 密钥绝不能透传给第三方 MCP server（多租户 + 不可信代码 = 凭证隔离是硬要求）。

| **维度** | **本机单用户** | **服务端多租户** |
| --- | --- | --- |
| **MCP子进程** | 跑在自己机器 | 跑在共享服务器 |
| **凭证** | 全透传自己承担 | 必须白名单隔离 |
| **泄露后果** | 影响一人 | 多租户灾难 |

**服务端 MCP 的两个独有要求**（面试可答）：

```Plaintext
凭证隔离   MCP 子进程不继承主机 env，按 Skill 白名单注入（防密钥泄露）
按租户身份 多用户 OAuth 场景倾向 MCP（而非 CLI 的单一身份）
```

**选型结论**：

```Plaintext
我们的 MCP = 统一注册表（已有）+ defer_loading + 凭证隔离

  接入：双传输 stdio/http（已有）+ 协议握手（已有）
  注册：统一注册表（已有，亮点）—— 内置+MCP 同一套权限/审计
  加载：defer_loading（按需，接 ch5）+ tool search
  凭证：白名单隔离（修全透传，服务端必修）
  互操作：mcp serve 记忆外放（架构方向）
  失败：断连重连（已有）+ 接第 10 章容错
```

---

## 11.4 适配：本机 → 服务端多租户

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| **三大 Agent（本机）** | **AgentForge（服务端多租户）** | **搬/改/弃** |
| --- | --- | --- |
| **统一注册表（CC）** | **已有**（McpTool implements Tool） | **已有** |
| **defer_loading（CC）** | 补（接 ch5） | **搬** |
| **tool search（CC）** | 补（语义发现） | **搬** |
| **凭证白名单隔离（Hermes）** | 直接用（修全透传） | **搬** |
| **mcp serve 记忆外放（Hermes）** | 架构方向 | **参考** |
| **MCPorter MCP→CLI（OpenClaw）** | 不需要 | **弃** |
| **全量加载（OpenClaw）** | 弃（改 defer） | **弃** |

---

## 11.5 落地：AgentForge 的 MCP 集成

### 11.5.1 总架构

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

### 11.5.2 现状诚实呈现：统一注册表是亮点；全量加载 + 凭证全透传

> 现状两个亮点 + 两个缺口：

```Plaintext
亮点①【统一注册表】（对标 Claude Code 统一门控）：
   Java：McpTool implements com.bitguide.tool.Tool
         registerTool 时和内置工具进同一个 Map，不分桶
   Go：  McpToolAdapter 实现 tool.Tool 接口
         Manager.Register 时和内置工具同一个 map
   → 内置/MCP 同一套注册/权限/审计路径（内外无别）
   → 加 MCP 工具不用改权限系统核心（开闭原则）

亮点②【双传输 + 教学示例】：
   stdio（ProcessBuilder/exec）+ http（OkHttp/net/http）工厂分发
   mcp-servers/：parenting/stock 示例 server
     注释明确"从内置工具迁移，业务工具与平台解耦"

缺口①【全量加载】（无 defer_loading）：
   连接 = 立即 listTools 拉全部工具的完整 schema
   Go：Client.initialize 末尾直接 c.listTools()（连接即全量）
   → 多 MCP server 时 schema 线性膨胀（4 server = 150K+ Token）

缺口②【凭证全透传】（安全隐患）：
   Java：ProcessBuilder.environment() 继承父进程【全部】env，putAll 只追加
   Go：client.go:75 NewStdioTransport 第三个 env 参数【硬编码 nil】
        → cmd.Env = nil → 继承父进程全部；连配置的 env 都没用上（bug）
   → 主机 API_KEY/密钥全透传给 MCP 子进程
   → 服务端多租户 + 第三方 MCP（不可信）= 灾难
```

> 框成设计：统一注册表 + 双传输是扎实的基础，扩展篇补 defer_loading（接 ch5 的按需加载）和凭证隔离（服务端安全必修）。

### 11.5.3 defer_loading（按需加载，接 ch5，关键实现示例）

> 现状：连接时 `listTools` 拉全部完整 schema。扩展篇改成两阶段——连接只载名称，调用时才载完整 schema。

```Java
// Java：defer_loading（连接只载名称，调用时才载 schema）
public class McpClient {
    // 现状：connect 时 listTools 拉完整 schema（全量）
    // 扩展篇：connect 时只拉 name + description（轻量），schema 懒加载
    public void connect() {
        transport.connect();
        this.toolNames = listToolNames();        // 只拉名称（几百 Token）
        // 完整 schema 不在此加载
    }
    public McpToolDefinition loadSchema(String toolName) {
        return toolSchemas.computeIfAbsent(toolName, this::fetchSchema); // 调用时才拉
    }
}
```

```Go
// Go：defer_loading
func (c *Client) Connect() error {
    c.initialize()
    c.toolNames = c.listToolNames()              // 只拉名称
    // schema 懒加载
    return nil
}
func (c *Client) LoadSchema(name string) *ToolDef {
    return c.schemas.LoadOrStore(name, c.fetchSchema(name))
}
```

```Python
class McpClient:
    async def connect(self) -> None:
        await self.transport.connect()
        # 连接期只保存轻量名称列表，不预付全部工具 schema 的上下文成本。
        self.tool_names = await self.list_tool_names()

    async def load_schema(self, name: str) -> ToolDefinition:
        if name not in self.schemas:
            self.schemas[name] = await self.fetch_schema(name)
        return self.schemas[name]
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **连接只载名称** | listToolNames（轻量） | 省 Token（4 server 不再 150K） |
| **schema 懒加载** | 调用时 fetchSchema | 按需支付（接 ch5） |
| **缓存** | computeIfAbsent | 不重复拉 |

### 11.5.4 凭证白名单隔离（修全透传，服务端必修，关键实现示例）

> 核心：MCP 子进程**不继承父进程全部 env**，只注入 Skill 显式声明的白名单变量（Hermes 模式）。这是当前最关键的安全修复。

```Java
// Java：凭证白名单注入（修 ProcessBuilder 全继承）
public class StdioMcpTransport {
    protected ProcessBuilder buildProcess() {
        ProcessBuilder pb = new ProcessBuilder(command);
        Map<String,String> clean = new HashMap<>();        // 干净环境，不继承父进程
        // 只注入 Skill 显式声明的白名单变量
        for (String allowed : config.getEnvWhitelist()) {
            if (config.getEnv().containsKey(allowed))
                clean.put(allowed, config.getEnv().get(allowed));
        }
        pb.environment().clear();                          // 清空继承的
        pb.environment().putAll(clean);                    // 只放白名单
        // 关键：不再 putAll(config.getEnv()) 全追加，不再继承主机 API_KEY
        return pb;
    }
}
```

```Go
// Go：凭证白名单注入（修 client.go:75 env=nil 全继承）
func NewStdioTransport(cmd, args []string, allowedEnv map[string]string) *StdioTransport {
    // 调用方传白名单 env（而非 nil），只含 Skill 声明的变量
    env := []string{}
    for k, v := range allowedEnv {                         // 只白名单，不含父进程
        env = append(env, k+"="+v)
    }
    return &StdioTransport{command: cmd, args: args, env: env}
}
// factory 调用处：NewStdioTransport(cmd, args, skill.DeclaredEnv()) // 不再传 nil
```

```Python
def build_stdio_environment(config: McpServerConfig) -> dict[str, str]:
    # 不复制 os.environ；只从配置中挑选 Skill 明确声明的变量。
    return {
        name: config.env[name]
        for name in config.env_whitelist
        if name in config.env
    }

process = await asyncio.create_subprocess_exec(
    config.command, *config.args,
    env=build_stdio_environment(config),
    stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
    stderr=asyncio.subprocess.PIPE,
)
```

```Plaintext
凭证隔离前后对比：
   之前：MCP 子进程继承父进程全部 env（API_KEY/SSH/数据库密码全透传）
          → 第三方恶意 MCP server 可读环境变量偷密钥
   之后：MCP 子进程只有 Skill 显式声明白名单变量
          → 主机密钥不泄露（哪怕 MCP server 是恶意的）
   → 服务端多租户 + 不可信第三方 MCP = 凭证隔离是硬要求
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **不继承父进程** | environment().clear() | 切断主机 env 透传 |
| **白名单注入** | 只 Skill 声明的变量 | 最小权（接 ch3 NHI） |
| **Go 修 nil bug** | 调用方传白名单 | 修 client.go:75 缺陷 |

### 11.5.5 统一注册表（已有，亮点，关键实现示例呈现）

> 已实现——内置工具和 MCP 工具走同一套注册/权限/审计路径。这是对标 Claude Code 统一门控的亮点，本章确认并讲透其价值。

```Java
// Java：统一注册表（已有）
// McpTool implements Tool → 和内置工具进同一个 ToolManager.tools Map
// → 内置/MCP 同一套：权限三层纵深（ch3）/ 沙盒（ch6）
public class McpTool implements Tool {
    public String getName() { return serverName + "_" + toolName; } // 避免命名冲突
    public ToolResult execute(Map<String,Object> params) {
        if (!transport.isConnected()) transport.reconnect();         // 断连重连
        return transport.sendRequest(buildCallTool(params));
    }
}
```

```Go
// Go：统一注册表（已有）
// McpToolAdapter 实现 tool.Tool → 同一个 Manager.tools map
type McpToolAdapter struct { client *Client; tool Tool }
func (a *McpToolAdapter) Execute(args map[string]any) (*tool.ToolResult, error) {
    if !a.client.IsAlive() { a.client.Reconnect(ctx) }              // 断连重连
    return a.client.CallTool(ctx, a.tool.Name, args)
}
```

```Python
class McpTool:
    def __init__(self, server_name: str, definition: ToolDefinition, client: McpClient) -> None:
        self.name = f"{server_name}_{definition.name}"  # 防止跨 Server 重名
        self.definition = definition
        self.client = client

    async def execute(self, args: dict[str, object]) -> ToolResult:
        if not self.client.is_alive:
            await self.client.reconnect()
        return await self.client.call_tool(self.definition.name, args)

# McpTool 与内置 Tool 注册到同一个 ToolManager，复用权限、沙盒和审计。
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **同一接口** | McpTool implements Tool | 内外无别 |
| **同一注册表** | 不分桶，同一个 map | 统一权限/审计/沙盒 |
| **命名防冲突** | serverName_toolName | 多 server 同名工具不撞 |

### 11.5.6 tool search + mcp serve（架构方向）

```Plaintext
tool search（语义发现，接 ch7 RAG 思路）：
   接多个 MCP server 后，遍历式 schema 注入会撑爆上下文
   → 用向量检索/关键词匹配在调用前缩小候选工具集
   → RAG 的 embedding 思路迁移到工具发现

mcp serve（记忆外放，架构方向）：
   把 DistributedMemory（Redis）作为 MCP 资源对外暴露
   → 其他 Agent / AI 工具可查我们的记忆
   → 跨 Agent 互操作（接 ch8 多 Agent 编排）
```

### 11.5.7 声明式配置（etcd 动态 MCP 策略）

```YAML
# MCP 声明式配置（etcd 下发）
mcp:
  servers:
    - { name: github, transport: http, url: https://... }
    - { name: parenting, transport: stdio, command: java ParentingMcpServer }
  loading:
    defer: true                  # defer_loading（连接只载名称）
    toolSearch: true             # 语义发现
  security:
    envIsolation: whitelist      # 凭证白名单隔离（不继承父进程）
    inheritParentEnv: false      # 关键：不透传主机 env
  registry:
    unified: true                # 内置+MCP 同一套（已有）
    namePrefix: true             # serverName_toolName 防冲突
  failure:
    reconnect: true              # 断连重连（已有）
    circuitBreaker: 3            # 接 ch10 容错
```

> 和核心卖点的关系：MCP server 配置走 etcd 动态下发——**不重启就能加/删 MCP server、调白名单、切 defer 模式**。`DynamicConfigManager` 监听，重连生效。

### 11.5.8 文件清单

| **文件** | **改动** | **说明** |
| --- | --- | --- |
| `McpClient.connect`**（已有改）** | 连接只载名称，schema 懒加载 | defer_loading（接 ch5） |
| `StdioMcpTransport`**（改）** | env clear + 白名单注入 | 凭证隔离（修全透传） |
| **Go **`NewStdioTransport`** 调用处（改）** | 传白名单而非 nil | 修 client.go:75 缺陷 |
| `McpTool`** / **`McpToolAdapter`**（已有）** | 不动（统一注册表亮点） | 内外无别 |
| `ToolSearch`**（新增）** | 语义发现候选工具 | tool search |
| `mcp serve`**（架构方向）** | 记忆外放 | 跨 Agent 互操作 |
| **etcd schema** | `mcp.*` 策略 | 声明式动态 MCP |

---

## 11.6 面试答法

| **面试官问** | **标准答法** |
| --- | --- |
| **外部工具怎么接？MCP 还是 CLI？** | 看场景：CLI 适合模型见过的高频本地操作（git/gh/aws，批量循环）；MCP 适合无 CLI 的 SaaS、需多用户 OAuth、需审计隔离的。我们双传输（stdio/http）都支持，统一注册表（内置+MCP 同一套门控）。 |
| **接多个 MCP 服务器，schema 会不会爆上下文？** | 会——4 个 MCP server 光 schema 就 150K+ Token。我们用 defer_loading：连接时只载工具名称列表，调用时才注入完整 schema（接第 5 章渐进式披露）。把"Token 税"从全量预付变按需支付。 |
| **MCP 工具和内置工具权限一样吗？** | 一样——统一注册表，McpTool implements Tool，内置和 MCP 进同一个 ToolManager map，不分桶。同一套权限三层纵深（ch3）、沙盒（ch6）。加 MCP 工具不改权限核心（开闭原则）。 |
| **MCP 子进程跑在服务器上，主机密钥会不会泄露？** | 默认会（现状缺口），扩展篇修：凭证白名单隔离——MCP 子进程不继承父进程全部 env，只注入 Skill 显式声明的白名单变量。服务端多租户 + 第三方 MCP 是不可信代码，主机 API Key 绝不能透传（对比 Hermes 白名单注入）。 |
| **MCP 工具断连/失败怎么办？** | McpTool.execute 先检测 isConnected，断了 reconnect。重连失败/调用失败接第 10 章容错（错误分类 + 重试 + 熔断）。连接是按 server 独立的，一个 server 挂不影响其他。 |
| **服务端和本机的 MCP 差在哪？** | 凭证隔离：本机 MCP 子进程跑在自己机器，全透传自己承担；服务端跑在共享服务器，主机密钥泄露 = 多租户灾难，必须白名单隔离。另外多用户 OAuth 场景服务端更倾向 MCP（而非 CLI 的单一身份）。 |
| **defer_loading 具体怎么做？** | 两阶段：连接时 listTools 只拉 name+description（几百 Token）；模型决定调用某工具时，再 fetchSchema 拉它的完整 JSON Schema 注入。缓存避免重复拉。和第 5 章 Skill 渐进式披露是同一个思想。 |
| **能不能把记忆共享给其他 Agent？** | 能（架构方向）——mcp serve 把 DistributedMemory 作为 MCP 资源对外暴露，其他 Agent/AI 工具可查。跨 Agent 互操作（接第 8 章多 Agent 编排）。 |

---

## 小结

1. **MCP vs CLI 看场景**：CLI 适合高频本地/批量；MCP 适合 SaaS/多用户 OAuth/需审计。

1. **统一注册表是亮点**：McpTool implements Tool，内置+MCP 同一套权限/审计/沙盒（已有，对标 Claude Code）。

1. **defer_loading 按需加载**：连接只载名称，调用才载 schema（接 ch5），治 4-server-150K-Token 的上下文杀手。

1. **凭证白名单隔离（服务端必修）**：MCP 子进程不继承主机 env，按 Skill 白名单注入——修当前全透传缺口（含 Go client.go:75 的 nil bug）。

1. **断连重连 + 容错**：isConnected 检测 → reconnect，失败接第 10 章。

1. **mcp serve 记忆外放**：架构方向，跨 Agent 互操作。

> 接下来：附录 A（目标漂移与上下文工程）、附录 B（工具描述规范）；或回到总纲（`00-总纲.md`）看「全篇能力地图」——部署语境前提贯穿所有章节，后续新增章节也会续进那张表。
