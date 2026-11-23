# Stage 3 Redis Cluster 多桶限流实验（Windows PowerShell）

**当前进度：集群已建好。** 你贴出的结果显示三个主节点均已连接，16384 个槽全部正常。现在在窗口 A 执行下面的“窗口准备”，然后直接进入第 2 节。第 1 节留给第一次搭建新环境时使用。

## 窗口准备

以下命令都在 Windows PowerShell 中输入。窗口 A 运行 Stage 3，窗口 B 发送请求并收集证据。**每个新窗口先执行一次此块**，因为两个窗口的环境变量独立。

```powershell
Set-Location -LiteralPath 'F:\Agent项目\AiAgentForge\bitguide-agent-platform-main'
$env:COMPOSE_FILE = (Resolve-Path '.\docker\docker-compose-java-20261027.yml').Path
$out = Join-Path $env:TEMP 'bitguide-crossslot'
New-Item -ItemType Directory -Path $out -Force | Out-Null
```

## 1. 首次搭建 Docker 和 Redis Cluster

使用 Docker Desktop 的 WSL 2 后端时，在 PowerShell 执行下面的命令启动桌面引擎。等待 Docker Desktop 就绪，再运行第二个代码块。

```powershell
Start-Process -FilePath "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe"
```

```powershell
docker info
docker compose config --services
docker compose up -d --wait redis-node-1 redis-node-2 redis-node-3
1..3 | ForEach-Object { docker compose exec -T "redis-node-$_" redis-cli PING }
docker compose exec -T redis-node-1 redis-cli CLUSTER INFO
```

若提示 6379 被占用，用 `docker ps --filter publish=6379` 查看占用者。如果是项目旧单机容器，可执行 `docker stop bitguide-redis` 后重新启动三个节点。单机配置与数据卷已保留。

**仅在三个新节点都响应 PONG，且均未分配槽时执行以下建群命令。** 已有集群直接进入第 2 节。

```powershell
$nodes = foreach ($i in 1..3) {
    $address = docker compose exec -T "redis-node-$i" hostname -i
    if ($LASTEXITCODE -ne 0) { throw "无法读取 redis-node-$i 地址" }
    $ip = ($address.Trim() -split '\s+')[0]
    "${ip}:6379"
}
docker compose exec -T redis-node-1 redis-cli --cluster create @nodes --cluster-replicas 0 --cluster-yes
docker compose exec -T redis-node-1 redis-cli CLUSTER NODES
docker compose exec -T redis-node-1 redis-cli CLUSTER INFO
```

就绪条件为 `cluster_state:ok`、`cluster_slots_ok:16384` 和 `cluster_size:3`。三个主节点各有独立数据卷，无副本。

## 2. 窗口 A：启动 Stage 3，保留完整日志

使用 Windows 上的 JDK 17、Maven 和项目原有的应用依赖。在完成“窗口准备”后执行：

```powershell
Set-Location -LiteralPath 'F:\Agent项目\AiAgentForge\bitguide-agent-platform-main'
$env:COMPOSE_FILE = (Resolve-Path '.\docker\docker-compose-java-20261027.yml').Path
$out = Join-Path $env:TEMP 'bitguide-crossslot'
New-Item -ItemType Directory -Path $out -Force | Out-Null
docker compose up -d bitguide-etcd milvus
$port = 8081
if ([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners().Port -contains $port) {
    throw "端口 $port 已占用，请使用已有实验实例或选择空闲端口。"
}
$log = Join-Path $out "stage3-$port-$([guid]::NewGuid().ToString('N')).log"
Write-Host "本次实例日志：$log"
mvn.cmd compile exec:java '-Dexec.mainClass=com.bitguide.stage3.Stage3App' "-Dexec.args=--port $port" 2>&1 | Tee-Object -FilePath $log
```

等待控制台显示 Redis 连接成功、分布式网关初始化完成和 Stage 3 服务已启动。保持窗口 A 运行。应用通过 localhost:6379 直连节点 1，本实验使用 8081，修改端口后须同步修改窗口 B 的请求地址。

## 3. 窗口 B：确认三个桶各自所在的主分片

新开 PowerShell，先执行“窗口准备”，然后按顺序执行第 3 至 5 节。实验使用已有的 test-key 认证为 test-user，并指定 probe-3。网关先检查限流，之后才执行 Agent。当前 Stage3App 从 gateway 顶层读取 globalMaxConcurrent，默认值 100 满足启用 Agent 桶的条件（大于 50）。

```powershell
Set-Location -LiteralPath 'F:\Agent项目\AiAgentForge\bitguide-agent-platform-main'
$env:COMPOSE_FILE = (Resolve-Path '.\docker\docker-compose-java-20261027.yml').Path
$out = Join-Path $env:TEMP 'bitguide-crossslot'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$keys = @(
    'bitguide:ratelimit:global:token_bucket'
    'bitguide:ratelimit:user:test-user:token_bucket'
    'bitguide:ratelimit:agent:probe-3:token_bucket'
)
& {
    docker compose exec -T redis-node-1 redis-cli CLUSTER NODES
    foreach ($key in $keys) {
        $slot = docker compose exec -T redis-node-1 redis-cli --raw CLUSTER KEYSLOT $key
        "$key  slot=$slot"
    }
} | Tee-Object -FilePath (Join-Path $out 'slots.log')
```

| 桶 | 槽号 | 你本次建群输出中的主节点 |
|---|---:|---|
| 全局 | 7620 | 172.16.253.2（5461–10922） |
| test-user | 2104 | 172.16.253.3（0–5460） |
| probe-3 | 13102 | 172.16.253.4（10923–16383） |

重建集群后以当次 CLUSTER NODES 输出为准。公共前缀 bitguide:ratelimit: 仍会参与整键哈希，上述三个键因此路由至不同槽。

## 4. 窗口 B：模拟用户请求，查看 Java 异常栈

使用 Windows 的 curl.exe 并从 UTF-8 文件读取 JSON，避免 PowerShell 版本差异影响引号传递。每次发送使用独立 sessionId，便于定位该次请求。

```powershell
$sessionId = 'crossslot-' + [guid]::NewGuid().ToString('N')
$requestPath = Join-Path $out 'request.json'
$json = @{ sessionId=$sessionId; userId='test-user'; agentId='probe-3'; message='请回复测试消息' } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($requestPath, $json, [Text.UTF8Encoding]::new($false))
curl.exe -sS -i 'http://localhost:8081/api/v1/chat' -H 'Content-Type: application/json' -H 'X-API-Key: test-key' --data-binary "@$requestPath" | Tee-Object -FilePath (Join-Path $out 'http-response.txt')
$log = Read-Host '粘贴窗口 A 输出的本次实例日志完整路径'
Select-String -Path $log -Pattern $sessionId,'CROSSSLOT','Redis 多桶限流操作失败','请求限流','tryAcquireTokens','RedisRateLimitManager.check','DistributedAgentGateway.process','AgentApiController.chat'
```

从 sessionId 对应的请求日志开始，在 stage3.log 中查看完整堆栈。调用顺序为 AgentApiController.chat → DistributedAgentGateway.process → RedisRateLimitManager.check → RedisClient.tryAcquireTokens → jedis.eval。Redis 检查 EVAL 声明的 KEYS 时返回 CROSSSLOT，Lua 尚未开始扣减。RedisClient 捕获异常后返回 false，网关返回 TOO_MANY_REQUESTS。外层 ApiResponse 的业务码为 500，HTTP 状态以 http-response.txt 首行为准。

## 5. 窗口 B：用同一 Lua 脚本对照跨槽与同槽结果

下面从 RedisClient.java 提取当前脚本，复制到节点 1 的临时目录。redis-cli 在容器网络内执行，可以通过 -c 跟随 MOVED。两组调用使用同一脚本、同一时间戳、相同的每桶 QPS。"--eval" 后的独立逗号分隔 KEYS 和 ARGV。

```powershell
$source = Get-Content -Raw -Encoding UTF8 '.\src\main\java\com\bitguide\redis\RedisClient.java'
$match = [regex]::Match($source, '(?s)String\s+MULTI_TOKEN_BUCKET_SCRIPT\s*=\s*"""\s*\r?\n(.*?)"""')
if (-not $match.Success) { throw '未找到 MULTI_TOKEN_BUCKET_SCRIPT' }
$luaPath = Join-Path $out 'multi-token-bucket.lua'
[IO.File]::WriteAllText($luaPath, $match.Groups[1].Value, [Text.UTF8Encoding]::new($false))
docker compose cp $luaPath 'redis-node-1:/tmp/multi-token-bucket.lua'
if ($LASTEXITCODE -ne 0) { throw '复制 Lua 失败' }
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$tag = "{s35-$now}"
$good = @("${tag}:global", "${tag}:user:test-user", "${tag}:agent:probe-3")
& {
    '跨槽组：预期 CROSSSLOT'
    docker compose exec -T redis-node-1 redis-cli -c --raw --eval /tmp/multi-token-bucket.lua @keys ',' $now 5 5 5
    '同槽组：预期三个相同的槽号'
    foreach ($key in $good) { docker compose exec -T redis-node-1 redis-cli CLUSTER KEYSLOT $key }
    '同槽申请：预期返回 0（全部通过）'
    docker compose exec -T redis-node-1 redis-cli -c --raw --eval /tmp/multi-token-bucket.lua @good ',' $now 5 5 5
} 2>&1 | Tee-Object -FilePath (Join-Path $out 'redis-control.log')
```

同槽组使用新标签，每个桶初始容量为 5，成功后各剩 4，状态会在 2 秒后过期。跨槽组应返回 CROSSSLOT。若出现 CLUSTERDOWN，重新检查第 1 节的集群就绪条件；若 HTTP 响应提示“服务未就绪”，检查窗口 A 的启动日志。

实验材料保存在 `$env:TEMP\bitguide-crossslot`，其中 slots.log 记录拓扑，request.json 和 http-response.txt 对应模拟请求，本次实例的 stage3-端口-GUID.log 保留启动日志和完整 Java 堆栈，redis-control.log 记录两组 Redis 原始结果。
