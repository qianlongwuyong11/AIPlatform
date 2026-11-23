# Stage 3 学习验收通关书

对应《第 6 章 迭代三设计详解》。终点是能从一次网页聊天出发，沿实际调用路径判断请求在哪里被拒绝、哪些状态跨实例共享，以及关闭或故障时调用方能看到什么。以下依据仓库源码静态核对，未启动 Redis、etcd 或 HTTP 服务。以下待答题均按分布式部署作答，每题提交一小段解释即可。S3.3 已有首次作答记录，其中涉及单机的历史补答建议可跳过。

## 两个已问问题

**API Key 存在哪里，鉴权时去哪里取？** Stage 3 启动时读取 [stage3.yml](bitguide-agent-platform-main/src/stage3/java/com/bitguide/stage3/config/stage3.yml)。映射位于 `gateway.auth.api-keys`，键是 API Key，值是 userId。[Stage3App.createGatewayConfig](bitguide-agent-platform-main/src/stage3/java/com/bitguide/stage3/Stage3App.java) 用 `putAll` 把映射复制到 [GatewayConfig.apiKeys](bitguide-agent-platform-main/src/main/java/com/bitguide/gateway/config/GatewayConfig.java) 的 `HashMap<String, String>`。配置文件保存原始配置，每个运行实例在自己的 JVM 堆中保有一份映射；这条鉴权路径没有逐请求读取文件、Redis 或 etcd。仓库样例在 YAML 中直接列出凭据，加载进内存本身不提供加密保护。

[AgentApiController.chat](bitguide-agent-platform-main/src/stage3/java/com/bitguide/stage3/server/controller/AgentApiController.java) 优先取 `X-API-Key`，然后尝试 `Authorization: Bearer …`，两者均未提供有效格式的值时会填入固定演示 token。启用鉴权后，[DistributedAgentGateway.process](bitguide-agent-platform-main/src/main/java/com/bitguide/gateway/distributed/DistributedAgentGateway.java) 执行 `gatewayConfig.getApiKeys().get(token)`，命中后写入 `context.authenticatedUserId`，未命中则提前返回。因此，演示 token 在映射中有效时，未带认证头的聊天请求也能以其对应用户的身份通过这一关。

**会话串行化解决什么实际问题？** 假设同一会话先发“把目的地改成上海”，第一轮仍在生成回复，用户又发“那里明天天气怎么样”。两个请求若同时读取和追加历史，第二轮可能看不到第一轮完整结果，回复也可能倒序写回。Stage 3 在记录用户消息、调用 Agent、记录助手回复之前按 sessionId 获取 Redis Lane，完成后在 finally 中离开。即使请求落到两个实例，只要使用同一 Redis、同一锁键且锁仍有效，它们也会互斥；不同会话可以并行。抢锁提供互斥，没有严格按到达顺序排队的承诺；等待超时会返回 SYSTEM_BUSY。跨实例历史是否新鲜还取决于记忆实现，见 S3.6。[RedisLaneManager](bitguide-agent-platform-main/src/main/java/com/bitguide/gateway/distributed/RedisLaneManager.java)

## 源码通关任务

### S3.1 一次聊天落到实例乙后怎样到达 Agent

- **目标与动作**　假设甲、乙都已加载目标 Agent，网页请求被转发到乙。沿 index.html 的 fetch、AgentApiController.chat 和 DistributedAgentGateway.process 追踪这条请求。
- **要求与提交物**　说明请求的 HTTP 路由、乙从哪里取得 Agent，以及网页从哪个响应字段取回回复。
- **通过标准**　能按调用顺序定位请求转换、注册表查询和回复封装，并说明由哪一个实例执行 Agent。

---

### S3.2 etcd 新增 Agent 后，两个实例何时能用它聊天

- **目标与动作**　甲、乙监听同一个 etcd，且最初都没有目标 Agent。新增一份有效配置后，甲已处理变更，乙尚未收到事件。阅读 [DynamicConfigManager](bitguide-agent-platform-main/src/main/java/com/bitguide/config/dynamic/DynamicConfigManager.java) 和 DistributedAgentGateway.process。
- **要求与提交物**　假设其他检查均通过，分别预测此时发给甲、乙的同一条聊天请求会走到哪里。
- **通过标准**　能追踪配置事件怎样改变各实例的 AgentRegistry，指出网关在哪里查找 Agent，以及乙具备处理该请求的条件。

---

### S3.3 过滤链如何在中途停止

- **目标与动作**　对照 §6.3、6.5，阅读 [DefaultFilterChain](bitguide-agent-platform-main/src/main/java/com/bitguide/gateway/filter/DefaultFilterChain.java)、[SecurityFilter](bitguide-agent-platform-main/src/main/java/com/bitguide/gateway/filter/SecurityFilter.java) 和 DistributedAgentGateway.process。以一条超过长度上限的消息为例，假设认证已通过、安全检查已启用；对照单机过滤链和 Stage 3 网关的校验循环。
- **要求与提交物**　解释两个入口各在哪里停止，以及后续校验和限流是否还会运行。
- **通过标准**　能说明过滤器排序、继续传递的条件，并按源码区分两个入口实际使用的短路机制。

#### S3.3 首次作答记录

**用户原文**

> 具体对应代码&#x5728;*// 3. 安全校验（输入长度、Prompt 注入检测、敏感词过滤）*
>
>             if (gatewayConfig.isSecurityEnabled()) {
>
>                 String message = *request*.getMessage();
>
>                 for (SecurityValidator validator : securityValidators) {
>
>                     SecurityValidator.ValidationResult result = validator.validate(message);
>
>                     if (!result.passed()) {
>
>                         log.warn("安全校验未通过 [{}]: reason={}, sessionId={}",
>
>                                 validator.getName(), result.blockedReason(), sessionId);
>
>                         return GatewayResponse.failure("SECURITY_BLOCKED", result.blockedReason());
>
>                     }
>
>                 }
>
>             }
>
> 其中，securityValidators当中包括多了实现了SecurityValidator接口的类，包括长度检测，敏感词检测等等。
>
> 在初始化安全校验器的时候，顺序为this.securityValidators.add(new InputLengthValidator(*gatewayConfig*));
>
>         this.securityValidators.add(new PromptInjectionValidator(*gatewayConfig*));
>
>         this.securityValidators.add(new SensitiveWordValidator(*gatewayConfig*));。
>
> 那么可以确定，先去执行长度安全校验，当长度安全校验失败之后直接返回，不会继续后续的校验，也就是直接进入 if (!result.passed()) {\
> &#x20;                       log.warn("安全校验未通过 [{}]: reason={}, sessionId={}",\
> &#x20;                               validator.getName(), result.blockedReason(), sessionId);\
> &#x20;                       return GatewayResponse.failure("SECURITY_BLOCKED", result.blockedReason());\
> &#x20;                   }条件内，后续的限流检查也不会执行。

**模型改正建议**

你已讲清 Stage 3 分布式网关的路径。长度校验器先执行，失败时的 return 退出整个 process 方法，因此其余校验器和限流检查都不会运行。

首次作答待补充单机入口。第二次只需沿 DefaultFilterChain 的排序和 SecurityFilter.filter 的两个返回点，用一句话说明安全与限流的实际先后、长度校验失败时链停在哪里，以及何时才会调用下一个过滤器。判断顺序时以 getOrder() 的数值为准。

---

### S3.4 两个实例争用最后一个 Redis 令牌会怎样

- **目标与动作**　阅读 [RedisClient.tryAcquireToken](bitguide-agent-platform-main/src/main/java/com/bitguide/redis/RedisClient.java) 的 Lua 脚本。甲、乙在相同时间戳各申请一个令牌，使用同一个桶键。桶内仅剩一个令牌，上次补充时间就是当前时间。
- **要求与提交物**　预测两个申请中有几个成功。假设桶容量为 2、每秒补 1 个令牌，恰好一秒后乙再次申请一个，结果如何？
- **通过标准**　能根据脚本对同一键的执行顺序、补充量和扣减条件解释三次申请，并说明能否预先指定首次成功的实例。

#### S3.4 首次作答记录

**用户原文**

> 首先，由于redis是单线程模型，同一时刻只能处理一个线程的请求，也就是说针对甲乙两个针对**tryAcquireToken的**请求，只能有一个执行。
>
> 因此，假设甲成功执行，进入脚本内部，由于上次补充时间就是当前的时间戳，因此补充令牌失败，考虑不到桶内部还剩下一个令牌，因此这次获取成功，获取成功后桶内剩余零个令牌。
>
> 接着，乙线程可以执行redis脚本，乙再去获取的话，可能由于当前的now-lastRedill可能已经大于零，但是还没有大于1，导致可能乙的这次请求可能不能够成功的补充一个令牌，而是补充0.几个令牌。这个我需要确定一下redis内部的浮点数设置时向上取整还是向下取整。
>
> 但是，题目说一秒钟之后乙再次申请，那么这次申请完成之后距离上次的补充已经过了1s，那么这次补充令牌成功，桶内总共剩余还剩下一个令牌，乙可以获取成功执行后续对应的操作。
>
> 因此我现在的抑或就是redis内部的浮点数是如何设置的，以及令牌的数量允许不允许是浮点数？

**模型改正建议**

你正确推演了甲先执行时扣掉最后一个令牌，以及乙一秒后能够获取令牌。首次作答还需修正一处关键判断：两个脚本都会执行，Redis 会让每次脚本从读取余额到写回状态完整执行。甲、乙谁先执行无法预先确定，先执行的一方拿到最后一个令牌。

题设给甲、乙的两个首次申请指定了相同的毫秒时间戳。Java 在调用 Redis 前就把这个值作为参数传入，乙即使排在甲后面执行，脚本里的 `now` 也仍等于甲刚写回的 `last_refill`。所以两次的补充量都恰好为 0。乙若排在第二，读到余额 0，申请失败。恰好一秒后，乙的补充量为 `1000 × 1 / 1000 = 1`，扣掉一个后余额回到 0。

**桶内余额可以有小数，每次申请的数量是整数。** [令牌桶脚本](bitguide-agent-platform-main/src/main/java/com/bitguide/redis/RedisClient.java) 用 `elapsed * rate / 1000` 计算补充量，只用 `math.min` 限制容量，没有向上或向下取整。举例说，空桶按每秒 1 个令牌补充，过 250 毫秒申请 1 个，算出的余额是 0.25。因为 `0.25 >= 1` 不成立，脚本拒绝申请，仍把 0.25 和本次时间写入 Redis 哈希。再过 750 毫秒，脚本用 `tonumber` 读回余额，加上 0.75，恰好可以扣 1 个。Java 收到的 0 或 1 只表示本次是否获准。

**状态**　首次作答待完善。第二轮只需用一句话串起三次申请，说明谁可能先成功、同一时间戳下后执行的一方补了多少，以及一秒后乙扣减后的余额。

---

### S3.5 跨实例限流会扣哪些额度

- **目标与动作**　对照 §6.6、6.8，阅读 [RedisRateLimitManager.check](bitguide-agent-platform-main/src/main/java/com/bitguide/gateway/distributed/RedisRateLimitManager.java) 和 [RedisClient.tryAcquireToken](bitguide-agent-platform-main/src/main/java/com/bitguide/redis/RedisClient.java)。设限流开启，两个实例共享 Redis 和键前缀，全局桶还有额度，但已认证用户的桶已耗尽。
- **要求与提交物**　解释该请求在哪个检查返回，以及前面已扣的额度如何变化。
- **通过标准**　能指出实际键的作用域、检查次序和 Redis 单次扣减的原子边界，并依据返回路径判断是否回补。

#### S3.5 首次作答记录

**用户原文**

由于**check的顺序是先检查全局限流然后再检查用户限流最后再检查agent限流。那么针对当前题设的请求，全局限流可以通过，在用户限流处请求令牌桶发现令牌桶内的令牌耗尽，那么也就是在**_// 2. 检查用户限流_

        if (userId != null && !checkUserLimit(userId)) {

            log.warn("用户限流触发: userId={}", userId);

            return GatewayResponse.rateLimited();

        }这个条件当中返回，而且，由于请求令牌桶会触发补充，如果elapsed>0的话，该用户的请求令牌桶当中的令牌会被补充，下次请求会成功。

**模型改正建议**

你找对了检查顺序和提前返回的位置。两个实例使用同一 Redis 和前缀，共享 `<前缀>global`，同一用户也共享 `<前缀>user:<userId>`。全局检查通过时已经扣了 1 个令牌。用户桶按时间补充后若仍不足 1 个，本次用户扣减为 0，立即返回限流响应，后面的 `<前缀>agent:<agentId>` 桶也没有被访问。

每次 Lua 调用只对一个键原子地完成读取、补充、判断和写回。用户检查失败的返回路径没有退还全局令牌。补充量按 `elapsed × rate / 1000` 计算，正的时间差可能只补到不足 1 个。下次请求要重新经过所有适用检查。

**状态**　首次作答待完善。请用自己的话说明，换一个实例重试时，全局桶为何仍体现上次扣减，以及用户桶要满足什么条件才能通过。

#### S3.5 第二次作答记录

针对当前情况，如果用户令牌补充失败的话，用户一直请求的话，没有退还全局限流桶的令牌，就会耗尽全局限流的令牌桶当中的令牌。

也就会造成，一个用户的短时间内的多次请求打满整个全局令牌桶的情况，这个是客观存在的因素对吧？

**模型补充与完善**

| 不足 | 完善理解 |
|---|---|
| 持续请求耗尽全局桶还需要流量条件。 | 每次通过全局检查就扣 1 个。若短时间内的请求足以用完现有余额和期间补入的额度，一个用户就可能使其他用户在全局检查处被拒。 |
| “补充失败”没有区分补充量和扣减门槛。 | Lua 按经过时间补充，即使补充成功，余额仍可能不足 1 个。此时用户桶扣 0 个，请求直接返回，Agent 桶也扣 0 个。 |
| 两轮回答尚未说明跨实例共享范围和原子边界。 | 两个实例共用 Redis 和前缀，访问同一全局键，同一用户也对应同一用户键。每次 Lua 调用只原子地处理一个桶，用户检查失败时，全局扣减已完成，返回路径没有退款。 |

**状态**　两轮验收已结束，模型已补全，独立掌握尚未证实。

---

### S3.6 两个实例读到的会话历史是否一致

- **目标与动作**　对照 §6.8，阅读 [DistributedMemory.getOrCreateContext](bitguide-agent-platform-main/src/main/java/com/bitguide/memory/DistributedMemory.java) 和 DistributedAgentGateway.process。实例甲已缓存会话 S，实例乙随后更新同一会话并写回 Redis；甲的本地缓存仍然命中。
- **要求与提交物**　预测甲下一次从哪里取上下文，以及它能否保证看到乙刚写入的内容。
- **通过标准**　能沿本地命中、Redis 加载和消息写回三个位置说明可见性，并区分网关的 sessionId 和传给 Agent 的会话键。

---

### S3.7 会话锁过期后，旧请求能否释放新请求的锁

- **目标与动作**　阅读 [RedisLaneManager](bitguide-agent-platform-main/src/main/java/com/bitguide/gateway/distributed/RedisLaneManager.java) 和 RedisClient.unlock。甲持锁处理会话 S，处理时间超过锁的有效期。乙随后取得 S 的锁，甲此时才进入清理逻辑。
- **要求与提交物**　按时间顺序说明两次 Agent 调用是否可能重叠，以及甲执行解锁后乙的锁是否还在。
- **通过标准**　能指出会话锁键、用于标识持有者的值和释放时的判断条件，并说明锁有效期怎样限制跨实例互斥。

---

### S3.8 Agent 抛异常后哪些状态保留下来

- **目标与动作**　对照 §6.2、6.10、6.11，阅读 DistributedAgentGateway.process 中的嵌套 try/finally。设甲处理会话 S、乙等待同一会话的 Lane。甲已进入 Lane、成功 acquire，用户消息也已写入，此时 agent.chat 抛出 Exception。
- **要求与提交物**　从抛错处追到控制器，用一段话解释历史记录、清理动作和响应。
- **通过标准**　能区分已写与尚未写的消息，说出引用释放与离开 Lane 的顺序，并区分网关错误码与控制器响应体的业务码。

---

### S3.9 甲滚动下线时，已到达甲的聊天能否完成

- **目标与动作**　对照 §6.10，阅读 [Stage3App.shutdownHook](bitguide-agent-platform-main/src/stage3/java/com/bitguide/stage3/Stage3App.java)、DistributedAgentGateway.shutdown 和 [AgentRegistry](bitguide-agent-platform-main/src/main/java/com/bitguide/agent/AgentRegistry.java)。跟踪关闭入口、线程池的有界等待和注册表的引用等待，并核对 HTTP 请求是调用 process 还是 processAsync。
- **要求与提交物**　判断仅凭这些代码能否保证滚动下线期间每个已经到达的 HTTP 请求都完成。
- **通过标准**　能指出各处实际等待的对象、超时后的动作，以及负载均衡摘流需要哪一层证据。

---

### S3.10 一次拒绝会出现在哪些监控数据里

- **目标与动作**　对照 §6.9、6.12，阅读 [AgentApiController](bitguide-agent-platform-main/src/stage3/java/com/bitguide/stage3/server/controller/AgentApiController.java)、[ChatMetrics](bitguide-agent-platform-main/src/stage3/java/com/bitguide/stage3/config/ChatMetrics.java) 和 [HealthController](bitguide-agent-platform-main/src/stage3/java/com/bitguide/stage3/server/controller/HealthController.java)。甲、乙分别暴露指标。假设指标已装配，在甲、乙上分别追踪一次网关返回 TOO_MANY_REQUESTS 和一次控制器因消息为空提前返回。
- **要求与提交物**　比较两次调用实际经过的指标记录语句，并判断只采集甲的指标会遗漏什么，以及访问乙的 /health 可以提供什么旁证。
- **通过标准**　能以实际分支说明拒绝计数、错误计数和耗时记录的覆盖范围，区分健康状态、业务错误码和章节列出的告警设想。
