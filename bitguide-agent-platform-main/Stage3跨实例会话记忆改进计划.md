# Stage 3 跨实例、跨 Agent 会话记忆改进计划

## 目标与现状

同一用户在同一个 sessionId 中切换 Agent 或服务实例后，下一次模型调用仍能看到保留窗口内的历史问答。不同会话保持隔离。本文是待实施方案。

当前 DistributedAgentGateway.process 按 sessionId 写 Redis，向 agent.chat 传递 sessionId:agentId。BaseAgent.chat 在调用模型前先写本地用户消息，buildChatHistory 与 buildChatHistoryWithPrompt 又从本地读取历史并追加本轮输入。仅增加“本地为空就查 Redis”会漏掉两种情况：刚写入本轮消息造成的假命中，以及请求回到旧实例时的过期命中。

## 实施顺序

1. **统一会话身份。** Stage 3 分布式入口向 Agent 传递原始 sessionId，检查 BaseAgent.buildFullSessionId 和清理入口，确保走同一套键规则。访问历史前校验会话归属。共享范围包括用户消息和助手的最终回复，助手消息保留 agentId。每次调用单独构建当前 Agent 的系统提示词，工具参数、工具原始输出和私有提示词不进入跨 Agent 共享历史。
2. **明确写入职责。** 建议在现有会话 Lane 内先读取已完成的历史，再执行 Agent；成功后由网关一次提交本轮用户消息、助手回复及新版本。用 Redis 事务或 Lua 原子校验预期版本并更新内容与 TTL，成功后更新本地副本。模型失败不提交半轮，Redis 提交失败不把本地标为最新。核查 Lane 是否跨实例生效及租期是否覆盖慢请求，版本冲突不得覆盖他人已提交的历史。需要支持请求重试时引入稳定 requestId 去重。
3. **改造读取路径。** 分布式模式为两个 buildChatHistory 入口提供同一个“已完成历史”快照。比较本地版本与 Redis 版本，命中直接使用本地内容，缺失或落后则读取 Redis 的一致快照并替换本地副本。Redis 每次提交一轮递增一个版本。本轮消息只在构造模型输入时追加一次。分布式路径移除 BaseAgent 中针对同一问答的提前写入；独立本地模式仍保留对话记忆。覆盖普通聊天和 Skill 自定义提示词入口。
4. **处理生命周期。** 对现有 Redis 会话 JSON 增加可选版本字段，读到旧格式时按版本 0 加载，首次提交时升级。保留原有 TTL 和历史窗口规则。会话清除、过期重建时更换会话代次，避免旧副本因版本号碰巧相同而复活。Redis 不可用时建议分布式请求返回可重试错误；如产品需要降级，应显式约定降级期间的连续性。

## 文件级实施清单

下表中 `main/` 指 `src/main/java/com/bitguide/`，`stage3/` 指 `src/stage3/java/com/bitguide/stage3/`。建议由网关读取快照并显式传给 Agent，两种历史构造方法消费同一份快照。这样普通 Agent 调用仍可使用原有本地入口。

| 文件 | 拟修改位置与内容 | 对应验收 |
|---|---|---|
| `main/gateway/distributed/DistributedAgentGateway.java` | 在 `process` 的 Lane 内校验归属、按原始 sessionId 取快照，移除拼接 agentId 的逻辑；将前后两次消息写入改为模型成功后一次提交整轮。清理路径同步失效缓存。 | 跨 Agent、跨实例、失败不留半轮 |
| `main/memory/DistributedMemory.java` | 改造 `getOrCreateContext`、Redis 读写及手工 JSON 序列化。现有 `localCache` 命中便直接返回，必须消除这条绕过版本校验的路径。建议分布式流程只保留 ConversationMemory 作为本地历史缓存。读取失败与会话不存在分别处理，提交失败向调用方传播。 | A → B → A 不返回旧快照，Redis 故障可见 |
| `main/redis/RedisClient.java` | 增加会话专用的原子提交操作，复用类内已有的 Jedis Lua 调用方式；比较代次和版本后更新 JSON、版本元数据及 TTL。提供轻量版本查询，保留原有会话 JSON 键以兼容 GET 验证。 | 版本冲突拒绝覆盖，两份键同步过期 |
| `main/memory/SessionContext.java`、`main/memory/Interaction.java` | 前者承载会话代次、版本和归属标识；后者为助手回复增加可选 agentId。同步更新 DistributedMemory 的手工读写映射，明确旧数据缺少归属时的迁移规则。 | 旧 JSON 可加载，回复来源可追溯 |
| `main/memory/ConversationMemory.java` | 为分布式路径增加按 sessionId 查询版本、整体替换已提交历史及失效操作。历史与版本一起发布，避免先更新版本再更新内容。 | 热缓存命中、清理后重建 |
| `main/agent/Agent.java`、`main/agent/BaseAgent.java` | 在接口增加接收已完成历史快照的入口；BaseAgent 将快照传入 `buildChatHistory` 和 `buildChatHistoryWithPrompt`，沿途覆盖普通聊天及 Skill 调用。分布式入口只追加一次当前输入，本地入口保留独立读写能力。 | 捕获两条调用路径的模型输入，逐条核对 |
| `main/skill/SkillManager.java` | 核对 Skill 匹配后回调 Agent 的位置；如回调重新进入自定义提示词聊天入口，在此透传同一快照。 | 命中 Skill 时仍能看到上一实例的历史 |
| `stage3/Stage3App.java` | 调整分布式网关的 Bean 装配，注入节点内共用的 ConversationMemory；核对 `agentFactory` 与网关拿到同一缓存实例。 | 双 JVM 各持独立本地缓存，连接同一 Redis |
| `src/test/java/com/bitguide/gateway/distributed/Stage3SharedHistoryTest.java`（新增） | 建立两个网关、两个独立缓存、同一个测试 Redis 和可捕获输入的模型；落实下表用例。 | 对应下方 Maven 专项命令 |

联调时核查 `main/gateway/distributed/RedisLaneManager.java` 的租期与释放行为；如果慢请求能跨越锁租期，仍须由提交时的版本校验阻止覆盖。HTTP 请求字段和会话键前缀沿用项目配置，可通过现有聊天接口执行双实例验收。

## 自动化验收

实施时新增 Stage3SharedHistoryTest，使用可捕获输入的假模型和两个独立的本地 memory。涉及 Redis 原子提交、TTL 和版本冲突的用例连接独立测试 Redis。直接断言发给模型的消息列表、角色、顺序及出现次数，避免依赖模型是否碰巧答对。

| 场景 | 通过标准 |
|---|---|
| 同 sessionId，A → B → A，中途换 Agent | B 看到第一轮，A 第三轮看到完整前两轮，当前输入一次，系统提示词属于当前 Agent |
| 同节点热缓存及冷启动 | 热缓存只查版本；新实例可从 Redis 恢复同一份历史 |
| 新会话、无权限访问、清除后重建 | 无其他会话消息；越权读取被拒绝；旧本地副本失效 |
| 自定义提示词及纯本地模式 | 当前消息各出现一次，连续两轮均能取得预期历史 |
| 并发提交、模型失败、Redis 写失败 | 无历史覆盖、无半轮、无虚假缓存命中 |
| 旧格式及超过保留窗口 | 可读取旧记录；截断后本地与 Redis 顺序及窗口一致 |

实施后在 PowerShell 中执行，要求 JDK 17、Maven 以及测试 Redis 就绪。确认 Maven 汇总中该类实际运行、失败数和错误数均为零。

```powershell
Set-Location 'F:\Agent项目\AiAgentForge\bitguide-agent-platform-main'
mvn '-Dtest=Stage3SharedHistoryTest' test
if ($LASTEXITCODE -ne 0) { throw '会话记忆专项测试失败' }
mvn test
if ($LASTEXITCODE -ne 0) { throw '全量测试失败' }
```

## 双实例命令行验收

沿用已能启动 Stage3App 的模型与依赖配置。两个 JVM 使用同一测试 Redis。以下按项目默认的 localhost:6379、DB 0 和会话键前缀编写。若 Redis 有认证，先为 redis-cli 配置测试凭据。Stage 3 的 API Key 填入请求终端的 BITGUIDE_TEST_API_KEY 环境变量。

在项目目录先执行一次编译并检查 Redis 返回 PONG。

```powershell
Set-Location 'F:\Agent项目\AiAgentForge\bitguide-agent-platform-main'
redis-cli -n 0 PING
mvn compile
```

在同一项目目录分别打开两个终端，各执行一条。若已通过 Cursor Debug 启动 8080 实例，直接复用它，只启动 8081。

```powershell
# 终端 A
mvn exec:java '-Dexec.mainClass=com.bitguide.stage3.Stage3App' '-Dexec.args=--port 8080'
# 终端 B
mvn exec:java '-Dexec.mainClass=com.bitguide.stage3.Stage3App' '-Dexec.args=--port 8081'
```

在第三个 PowerShell 终端执行。确认两个实例均进入分布式模式。每次生成全新的会话和随机标记，以便只检查本次记录。

```powershell
$sid = [guid]::NewGuid().ToString()
$marker = [guid]::NewGuid().ToString('N').Substring(0, 10)
$headers = @{}
if ($env:BITGUIDE_TEST_API_KEY) { $headers['X-API-Key'] = $env:BITGUIDE_TEST_API_KEY }
function Send-Turn($port, $agent, $message) {
    $body = @{ sessionId = $sid; agentId = $agent; message = $message } | ConvertTo-Json -Compress
    $r = Invoke-RestMethod -Method Post -Uri "http://localhost:$port/api/v1/chat" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    if ($r.code -ne 200) { throw "业务请求失败 $($r.code) $($r.message)" }
    $r.data | ConvertTo-Json -Depth 6
}
Send-Turn 8080 'parenting_advisor' "请记住本次会话的随机标记 $marker"
Send-Turn 8081 'stock_assistant' '请重复刚才的随机标记，并记住我的第二个代号是蓝鲸。'
Send-Turn 8080 'parenting_advisor' '请同时给出我的随机标记和第二个代号。'
redis-cli -n 0 --raw GET "bitguide:session:$sid"
redis-cli -n 0 TTL "bitguide:session:$sid"
```

第二次回复应包含随机标记，第三次应同时包含标记与“蓝鲸”。Redis 应出现同一会话的三轮有序问答，按本方案版本应为 3，TTL 为正。GET 对应沿用现有单键 JSON 的实现。模型回答用于人工观察，自动化测试捕获的真实输入决定历史拼装是否通过。若出现 401，先检查 API Key；若 Redis 中没有记录，先确认两个服务使用的数据库及分布式启动状态。

可在 Cursor 给历史同步入口打断点：第一次访问 B 时应看到冷缓存回填，从 B 回到 A 时应看到版本落后触发回填。记录两个端口、sessionId、三次提交版本及专项测试结果，作为验收证据。
