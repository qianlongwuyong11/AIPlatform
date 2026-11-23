# 第 7 章 · RAG 在 agent 平台的集成：Lost-in-Middle + 热度优化 + 多租户 + 与项目二的分工

> **为什么有这章**：原项目 RAG 是基线版（纯稠密向量 + Milvus），检索结果直接堆上下文中间、不按租户隔离——效果欠佳。这章补平台集成层（防 Lost-in-Middle、大文档外部化、按租户隔离、按访问热度优化）；检索质量深度优化（混合检索/rerank）交给配套的【项目二 DeepRAG】。对应学员反馈"按 RAG 访问情况优化"。

## 这篇讲什么

先说清楚一个分工：我们 agent 平台里的 RAG 是**"基线版"——纯稠密向量检索（Ollama nomic-embed-text + Milvus + scoreThreshold）**，效果欠佳是有意为之。因为 RAG 的深度优化（混合检索、rerank、chunking、query 改写、评估）本身是个大课题，我们**专门开了【项目二 DeepRAG】去做**——那是一个独立项目，从检索方案选型到前沿 chunking、query 理解、评估方法论全覆盖。本章不重复那些。

本章只讲 RAG 作为 **agent 平台组件**、项目二不管的几件事——也就是检索结果拿到之后，在平台里怎么被正确用：

- 检索结果塞进上下文**摆中间 = 白检索**（Lost in the Middle）→ 关键信息钉头尾

- **大返回值外部化**（长文档不撑爆 agent 上下文）

- **按访问热度优化**（多租户平台独有，学员痛点原话）

- **按租户隔离**检索结果

- 怎么**接声明式核心**（etcd 动态 RAG 策略）

> 一句话切分：**项目二管"检索质量"（怎么搜得又全又准），本章管"检索结果在平台里怎么被正确用"**。

**核心模型：平台集成层的四件事。** 项目二产出"又全又准的检索结果"，进了 agent 平台后，本章管这四件：

```Plaintext
项目二的检索结果 → 进 agent 平台 → 本章管：
  ① 摆放：最相关钉头尾（防 Lost in the Middle，U 形注意力）
  ② 外部化：大文档 >50K → 2KB 摘要 + 引用（不撑爆上下文）
  ③ 热度：高频 chunk 预计算/缓存/摘要（多租户平台独有）
  ④ 隔离：检索带 tenantId filter（A 查不到 B）
  + 接声明式核心：这些策略走 etcd 动态下发
```

**三个关键认知（想通这三个，这章就懂了）：**

- **检索质量归项目二，平台集成归本章**：召回怎么更全（混合检索）、排序怎么更准（rerank）、分片怎么切（chunking）、query 怎么改写、效果怎么评估——这些是项目二的检索质量深度优化，本章不展开；本章聚焦的是"结果拿到手之后，在 agent 平台里怎么不被浪费、不串租户、不重复算"。

- **Lost in the Middle 是 agent 上下文问题，不是检索问题**：哪怕项目二把检索做到完美，结果堆在上下文中间模型也"看得见用不到"（U 形注意力）。所以"摆放"（最相关钉头尾）是 agent 平台必须做的，和检索质量正交。

- **"按访问热度优化"是服务端独有**：本机单用户的知识库访问稀疏，没这个需求；服务端共享知识库 + 高并发，访问天然有冷热分布——高频 chunk 预计算/缓存/摘要（学员痛点原话"看高频向量、总结高频"）+ 按租户隔离，这两层是本机工具和项目二都不覆盖的。

> 这是这章的骨架。每件事怎么落、关键实现示例代码长啥样，往下看 §7.2 起。**细节是用来查的，不用全记**；先把"项目二管检索质量、本章管平台集成"这个分工和四件事想清楚。

---

## 7.1 痛点：基线 RAG 够用吗？检索结果在平台里怎么用

学员反馈原话：

> "生产环境根据 RAG 数据库访问情况优化（看高频向量、总结高频）。"

这个反馈点出的恰恰是**平台层**的 RAG 问题——不是"检索不准"（那是项目二），而是"生产环境怎么按访问情况优化"。围绕它，面试官会追问：

```Plaintext
你们 agent 平台的 RAG 怎么做的？
   └─ 检索质量（混合检索/rerank/...）—— 这块你们怎么做？
       └─ 【答：检索质量深度优化是项目二（DeepRAG），平台这边接它的结果】
           └─ 检索结果塞进 agent 上下文中间，模型看得到吗？（Lost in the Middle）
               └─ 返回的大文档会不会撑爆上下文？（外部化）
                   └─ 多租户共用知识库，A 能查到 B 的吗？（隔离）
                       └─ 生产环境哪些知识被反复查？怎么据此优化？（热度）
```

**现状诊断：有意做成基线版**

基于真实代码核对（Java `rag` 包 + Go `pkg/rag`），agent 平台的 RAG 是这样：

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

> 为什么平台 RAG 故意只做基线：agent 平台的核心卖点是声明式 + 动态配置 + 多 Agent 编排，RAG 是它的一个**可插拔组件**。把 RAG 检索质量做到生产级是个独立大工程（项目二），全塞进 agent 平台会失焦。所以平台这边只保证"基线能跑 + 平台集成层到位"，深度优化走项目二。

---

## 7.2 与项目二（DeepRAG）的分工

这一节把边界彻底讲清楚，避免学员搞混"哪些在 agent 平台做、哪些在项目二做"。

```Plaintext
RAG 的两层（别混）：

  【检索质量层】—— 项目二 DeepRAG 专攻
     · 混合检索（稠密 + BM25/ColBERT/SPLADE + RRF 融合）→ 检索方案选型
     · rerank（cross-encoder 精排）→ 贯穿 Stage2-4
     · chunking（前沿分片策略）→ 前沿 Chunking 方案
     · query 理解/改写 → Query 理解进阶
     · 评估方法论 → RAG 评估方法论
     · 还有 GraphRAG、多模态、文档解析、安全、生产规模化等扩展
     → 目标：搜得又全又准

  【平台集成层】—— 本章（agent 平台扩展篇）专攻
     · Lost in the Middle（结果摆中间=白检索）→ 钉头尾
     · 大返回值外部化（长文档不撑爆上下文）
     · 按访问热度优化（多租户平台独有）
     · 按租户隔离（A 查不到 B）
     · 接声明式核心（etcd 动态 RAG 策略）
     → 目标：项目二的检索结果，在平台里被正确用
```

**为什么这么切**：检索质量是"RAG 本身怎么变强"（项目二的课题，和是不是 agent 平台无关）；平台集成是"RAG 作为 agent 的一个组件，在多租户服务端怎么用对"（agent 平台独有，项目二不覆盖——它不涉及多租户隔离、不涉及大返回值外部化）。两层正交，各管各的。

> 学员面试答这个分工，直接显出"我们不是把 RAG 一锅炖，而是分了检索质量和平台集成两层，各有专门的项目/章节"——这是工程化的清晰度。

---

## 7.3 Lost in the Middle：检索结果摆中间 = 白检索

这是 RAG 最容易被忽略、却最致命的问题——**而且它是 agent 上下文问题，不是检索问题**：哪怕项目二把检索做到完美，结果堆在上下文中间，模型也"看得见用不到"。

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

三大 Agent 的应对（重启/截流/固定三策）：

```Plaintext
OpenClaw    ──重启──▶ 心跳 30 分钟周期性换新 Session，重要信息重新注入开头
                       （治标，单 Session 长任务无保护）
Claude Code ──截流──▶ ① 入口截流：工具输出 >50K 字符外部化（2KB 摘要 + 引用）
                       ② 主动钉住：Session Memory 结构化笔记（Decisions 字段）
                          压缩时回填到系统提示开头
                       （三者中最系统）
Hermes      ──固定──▶ 系统提示（SOUL/MEMORY/USER.md）冻结在开头，永不被挤走
                       Compaction 后结构化摘要回填前部
                       （简单有效，但不控中间积累速度）

   共同前提：Lost in the Middle 无法消除，只能控制损害
```

> 教学点：项目二也会提 Lost in the Middle（它在 chunking/解析里涉及），但**本章的角度是 agent 上下文怎么摆放检索结果**——这是 agent 平台必须做的，和检索质量正交。召回再多（项目二的事），摆中间也是白搭，所以"摆放"（最相关钉头尾）本章来管。

---

## 7.4 大返回值外部化：长文档不撑爆 agent 上下文

RAG 检索回来的长文档本身就是"大返回值"，会撑爆上下文。两大解法（这俩项目二不涉及，是 agent 平台侧的）：

```Plaintext
解法A：返回值外部化（Claude Code，框架级强制）
   文档 >50,000 字符 ──写临时文件──▶ 上下文只注入 2KB 摘要 + 引用
   模型按需 read_file 取细节
   → "摘要常驻 + 细节懒加载"

解法B：execute_code 下沉（Hermes，数据处理搬进工具层）
   原始日志 10 万行 ──模型写脚本处理──▶ 只返回几十 Token 的摘要
   结构化数据走 execute_code 返回摘要；非结构化才直接读取
   → "模型只看处理结果，不看原始数据"

   对 RAG 的启发：
   检索回来的大文档/大表 ──先在工具层聚合成摘要──▶ 再进上下文
   而不是把原始分片一股脑塞进去
```

> 这一节和第 6 章（工具沙盒的"输出侧防线"）是同一件事的两面：工具返回值要外部化，RAG 检索结果也是"返回值"，同样要外部化。

---

## 7.5 选型：服务端多租户语境下的 RAG 集成

> 关键差异：本机单用户，**知识库是本地文件，单用户访问，检索一次算一次，无热度分析需求**；**服务端多租户，共享知识库 + 高并发访问**——访问热度成了优化杠杆（学员痛点原话），还要按租户隔离检索结果。

| **维度** | **本机单用户** | **服务端多租户** |
| --- | --- | --- |
| **知识库** | 本地文件 访问稀疏 | 共享Milvus 高并发 冷热分布 |
| **热度优化** | 无需求 | 核心杠杆 学员痛点 |
| **隔离** | 无 | 按租户 collection/filter |

**服务端 RAG 集成的三个独有优化**（面试可答）：

```Plaintext
按访问热度   统计高频 chunk → 预计算/缓存/摘要（学员痛点原话）
按租户隔离   检索带 tenantId filter（A 查不到 B 的知识，接 ch2）
冷热分级     热区常驻缓存，冷区按需检索（性能 + 成本）
```

> 注意：这三条都是**平台集成层**的优化，和"检索质量"（混合检索/rerank，项目二）是两码事——就算项目二把召回/排序做到极致，平台层不做热度/隔离，照样慢、照样串租户。

**选型结论**：

```Plaintext
我们的 RAG 集成 = 接项目二的检索结果 + 平台四件事

  检索质量：接项目二（DeepRAG）的检索器（混合检索/rerank 在那实现）
  摆放：Lost in the Middle 防护（最相关钉头尾）—— 本章
  外部化：大文档 >50K → 2KB 摘要 + 引用（接 ch6）—— 本章
  热度：高频 chunk 预计算 + 缓存 + 摘要常驻热区（学员痛点）—— 本章
  隔离：检索带 tenantId filter / collection（接 ch2）—— 本章
```

---

## 7.6 适配：本机 → 服务端多租户

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

| **做法** | **AgentForge（服务端多租户）** | **搬/改/弃** |
| --- | --- | --- |
| **检索质量（混合/rerank/chunking）** | **接项目二 DeepRAG**，不在平台重做 | **接（项目二）** |
| **50K→2KB 外部化** | 直接用（接 ch6） | **搬** |
| **Lost in Middle 钉头尾** | 直接用（agent 上下文角度） | **搬** |
| **按访问热度优化** | 服务端独有（本机无需求） | **新增** |
| **按租户隔离** | 服务端独有 | **新增** |

---

## 7.7 落地：AgentForge 的 RAG 平台集成

### 7.7.1 总架构

<!-- 画板未能抓取（请把画板滚动到可见区域后重试，或在飞书里另存为图片手动插入） -->

### 7.7.2 现状：基线纯稠密向量（诚实呈现）

> 现状：`EmbeddingService`（Ollama nomic-embed-text）+ Milvus + scoreThreshold，纯稠密向量相似度。检索质量深度优化（混合/rerank）走项目二，不在平台。本章补的是平台集成层（摆放/外部化/热度/隔离）。

```Java
// Java 现状：纯稠密向量检索（RagService）
public List<Chunk> retrieve(String query, String tenantId) {
    float[] vec = embeddingService.embed(query);        // Ollama nomic-embed-text
    return milvus.search(vec, topK, scoreThreshold);    // 纯相似度，无 BM25、无 rerank（走项目二）
    // 结果直接拼进 prompt 中间 —— Lost in the Middle 未处理（本章补）
}
```

```Go
// Go 现状：纯稠密向量
func (s *RagService) Retrieve(query, tenantID string) []Chunk {
    vec := s.embedding.Embed(query)                     // Ollama
    return s.milvus.Search(vec, topK, scoreThreshold)   // 纯相似度
}
```

```Python
async def retrieve(self, query: str, tenant_id: str) -> list[Chunk]:
    # 基线只做稠密向量相似度；混合检索与重排属于 DeepRAG 的职责。
    vector = await self.embedding.embed(query)
    return await self.milvus.search(
        vector, top_k=self.top_k, score_threshold=self.score_threshold,
        filters={"tenant_id": tenant_id},
    )
```

### 7.7.3 检索质量：接项目二的结果（不重做）

> 平台不重新实现混合检索/rerank（那是项目二的活），而是**通过接口接项目二的检索器**，拿到"又全又准"的结果后，做平台层的包装（摆放/外部化/热度/隔离）。下面是平台侧的薄适配，混合/rerank 的实现细节看项目二。

```Java
// Java：平台侧 RagService 接项目二的检索器 + 平台层包装
public List<Chunk> retrieve(String query, String tenantId) {
    // ① 热区优先（高频 chunk 命中就省掉检索）
    List<Chunk> hot = hotCache.lookup(query, tenantId);
    if (hot != null) return hot;
    // ② 检索：接项目二的检索器（混合检索/rerank 在那实现），带租户 filter
    List<Chunk> retrieved = deepRagRetriever.search(query, tenantFilter(tenantId));
    accessLog.record(tenantId, retrieved);              // 记访问（热度统计源）
    return retrieved;                                    // 拿到结果后，交给摆放/外部化（见 7.7.4/7.7.5）
}
// 注：deepRagRetriever 是项目二的检索器接口；混合检索、rerank、chunking、query 改写都在项目二
```

```Go
// Go：平台侧接项目二检索器
func (s *RagService) Retrieve(query, tenantID string) []Chunk {
    if hot := s.hotCache.Lookup(query, tenantID); hot != nil { return hot } // 热区优先
    retrieved := s.deepRagRetriever.Search(query, tenantFilter(tenantID))    // 接项目二
    s.accessLog.Record(tenantID, retrieved)                                  // 记访问
    return retrieved
}
```

```Python
async def retrieve(self, query: str, tenant_id: str) -> list[Chunk]:
    if hot := await self.hot_cache.lookup(query, tenant_id):
        return hot
    # 平台只传递租户约束并消费 DeepRAG 结果，不在这里复制检索算法。
    chunks = await self.deep_rag.search(query, filters={"tenant_id": tenant_id})
    await self.access_log.record(tenant_id, chunks)
    return chunks
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **检索质量** | 接项目二（DeepRAG）检索器 | 不在平台重做，避免和项目二重复 |
| **热区优先** | 高频 chunk 命中省检索 | 学员痛点"看高频" |
| **租户 filter** | 检索带 tenantFilter | 多租户隔离（接 ch2） |
| **访问日志** | 记 retrieve | 热度统计源 |

### 7.7.4 Lost in the Middle 防护（关键信息钉头尾）

```Java
// Java：摆放——最相关的钉头尾，不堆中间
public class ContextPlacer {
    public String place(String systemPrompt, List<Chunk> retrieved, String userQuery) {
        // 最相关的两条放【开头】（高权重）和【结尾】（高权重）
        Chunk top = retrieved.get(0);
        return systemPrompt + "\n[关键参考]\n" + top.text()        // 钉开头
             + "\n...\n" + middle(retrieved)                        // 其余放中间
             + "\n[最相关]\n" + top.text() + "\n" + userQuery;      // 再钉结尾
    }
}
```

```Plaintext
摆放策略（防 U 形衰减）：
   开头：系统提示 + 最相关 chunk（高权重）
   中间：其余召回 chunk（低权重，但有摘要）
   结尾：最相关 chunk 重述 + 用户问题（高权重）
   → 最相关的信息出现在模型注意力最高的两个位置（头、尾）
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **最相关钉头尾** | top chunk 放开头 + 结尾 | 防 U 形衰减 |
| **中间只放摘要** | 其余 chunk 压缩 | 降低中间噪音 |
| **60% 主动压缩** | 不等 90% | 越早整理质量越好 |

### 7.7.5 按访问热度优化（学员痛点原话，服务端独有，重点）

> 这是学员反馈的核心："看高频向量、总结高频"。服务端多租户共享知识库，访问有冷热分布——**统计哪些 chunk 被反复检索，据此预计算/缓存/摘要**。这一层项目二不覆盖（它管检索质量，不管多租户访问模式）。

```Plaintext
按访问热度优化（服务端独有，四步）：
   ① 访问日志    每次 retrieve 记一条（tenantId + chunkId + 时间）
   ② 热度统计    定时聚合（Cron，接 ch8 主动 Agent）→ 高频 chunk 排行
   ③ 高频预计算  高频 chunk 的 embedding 预算好缓存（省重复 embed）
                 高频 chunk 总结成摘要，常驻【热区】（检索先命中热区）
   ④ 冷热分级    热区常驻缓存（快）；冷区按需检索（省资源）

   效果：
   · 高频查询命中热区缓存 → 省掉 embed + 向量搜索（延迟↓成本↓）
   · 高频 chunk 有预生成摘要 → 直接给摘要，不返原始大文档
   · 冷数据不占缓存 → 资源用在刀刃上
```

```Java
// Java：访问热度优化
public class HotChunkOptimizer {
    // 检索时：先查热区缓存
    public List<Chunk> retrieve(String query, String tenantId) {
        List<Chunk> hot = hotCache.lookup(query, tenantId);  // 热区命中？
        if (hot != null) return hot;                         // 命中 → 省掉 embed+search
        List<Chunk> fresh = ragService.retrieve(query, tenantId); // 未命中 → 走检索（接项目二）
        accessLog.record(tenantId, fresh);                   // 记访问（热度统计源）
        return fresh;
    }
    // Cron 定时：聚合访问日志 → 高频 chunk 预计算 embedding + 生成摘要 → 进热区
    public void refreshHotZone() {
        List<HotChunk> hot = accessLog.topChunks(7 /*天*/);
        for (HotChunk c : hot) {
            hotCache.put(c, embedCache.precompute(c), summarizer.summarize(c));
        }
    }
}
```

```Go
// Go：访问热度优化
func (o *HotChunkOptimizer) Retrieve(query, tenantID string) []Chunk {
    if hot := o.hotCache.Lookup(query, tenantID); hot != nil { return hot } // 热区命中
    fresh := o.ragService.Retrieve(query, tenantID)                          // 未命中走检索
    o.accessLog.Record(tenantID, fresh)                                      // 记访问
    return fresh
}
func (o *HotChunkOptimizer) RefreshHotZone() {              // Cron 定时
    hot := o.accessLog.TopChunks(7)
    for _, c := range hot {
        o.hotCache.Put(c, o.embedCache.Precompute(c), o.summarizer.Summarize(c))
    }
}
```

```Python
async def retrieve(self, query: str, tenant_id: str) -> list[Chunk]:
    if hot := await self.hot_cache.lookup(query, tenant_id):
        return hot
    chunks = await self.rag_service.retrieve(query, tenant_id)
    await self.access_log.record(tenant_id, chunks)
    return chunks

async def refresh_hot_zone(self) -> None:
    for chunk in await self.access_log.top_chunks(days=7):
        embedding, summary = await asyncio.gather(
            self.embed_cache.precompute(chunk), self.summarizer.summarize(chunk)
        )
        await self.hot_cache.put(chunk, embedding, summary)
```

| **设计点** | **方案** | **教学价值** |
| --- | --- | --- |
| **访问日志** | 每次 retrieve 记一条 | 热度统计源（学员痛点"看高频"） |
| **热区缓存** | 高频 chunk 预计算 + 摘要常驻 | 命中热区省 embed+search |
| **高频摘要** | 总结高频 chunk | 直接给摘要不返大文档（学员痛点"总结高频"） |
| **冷热分级** | 热区常驻/冷区按需 | 资源用在刀刃上 |
| **Cron 刷新** | 定时聚合（接 ch8 主动 Agent） | 热度随访问变化更新 |

### 7.7.6 声明式配置（etcd 动态 RAG 策略）

```YAML
# RAG 声明式配置（etcd 下发）
rag:
  retrieval:
    provider: deepRag          # 检索质量接项目二（DeepRAG）
    recallK: 20                # 召回数（项目二的检索器参数）
    topK: 5                    # 返回数
  placement:
    lostInMiddleGuard: true    # 最相关钉头尾（本章）
    middleSummary: true        # 中间只放摘要
  externalization:
    overflowChars: 50000       # 大文档外部化（接 ch6）
    summaryChars: 2048
  hotZone:
    enabled: true              # 按访问热度优化（学员痛点，本章）
    refreshCron: "0 */6 * * *" # 每 6 小时刷新热度
    hotTopN: 100               # 热区容量
    precomputeEmbedding: true  # 高频 chunk 预计算 embedding
    summarizeHot: true         # 高频 chunk 生成摘要
  tenantIsolation: true        # 按 tenantId filter（接 ch2）
```

> 和核心卖点的关系：RAG 集成策略（摆放/外部化/热度参数）走 etcd 动态下发——**不重启就能调热区刷新频率、改外部化阈值、切检索 provider**。`DynamicConfigManager` 广播，落到 RagService。

### 7.7.7 文件清单

| **文件** | **改动** | **说明** |
| --- | --- | --- |
| `RagService.retrieve` | 接项目二检索器 + 平台层包装 | 检索质量走项目二，平台不重做 |
| `ContextPlacer`**（新增）** | 最相关钉头尾 | Lost in the Middle 防护 |
| `OverflowStore`**（ch6 已有）** | 大文档外部化 | 长文档不爆上下文 |
| `HotChunkOptimizer`**（新增）** | 访问日志 + 热区缓存 + 摘要 | 按访问热度优化（学员痛点） |
| `AccessLog`**（新增）** | 记 retrieve 访问 | 热度统计源 |
| **etcd schema** | `rag.*` 策略 | 声明式动态 RAG 集成 |
| **检索质量（混合/rerank/chunking）** | **项目二 DeepRAG** | 不在 agent 平台本章 |

---

## 7.8 面试答法

| **面试官问** | **标准答法** |
| --- | --- |
| **你们 RAG 怎么优化的？** | 分两层：**检索质量**（混合检索/rerank/chunking/query 改写/评估）是专门的【项目二 DeepRAG】在做，是个独立项目；**平台集成层**（就是本章）管检索结果在 agent 平台里怎么用——防 Lost in the Middle、大返回值外部化、按租户隔离、按访问热度优化。我们没把 RAG 一锅炖，而是分了检索质量和平台集成两层，各有专门项目/章节。 |
| **agent 平台的 RAG 现在是什么水平？** | 诚实说：基线版（纯稠密向量 + Milvus），效果欠佳是有意为之——因为检索质量深度优化整体交给了项目二，agent 平台这边只保证"基线能跑 + 平台集成层到位"，避免失焦。 |
| **检索结果塞上下文，模型看得到吗？** | Lost in the Middle——长上下文注意力 U 形，中间被稀释。我们把最相关 chunk 钉在开头和结尾（高权重位），中间只放摘要。这是 agent 上下文问题，和检索质量正交——哪怕项目二把检索做到完美，摆中间也白搭。 |
| **生产环境怎么按访问情况优化？** | 按访问热度：每次检索记访问日志，定时（Cron）聚合高频 chunk，预计算它的 embedding（省重复 embed）+ 生成摘要常驻热区。高频查询命中热区缓存（延迟↓成本↓），冷数据按需检索。这是服务端独有——本机单用户访问稀疏，没这个需求；项目二也不覆盖（它管检索质量，不管多租户访问模式）。 |
| **多租户的知识库怎么隔离？** | 检索带 tenantId filter（或按租户 collection），A 查不到 B 的知识，接第 2 章。热度统计也按租户——不同租户的高频 chunk 不同，热区按租户分。 |
| **长文档会不会撑爆上下文？** | 外部化——单条 >50K 字符写临时文件，上下文只留 2KB 摘要 + 引用，模型按需 read（接第 6 章工具沙盒的输出侧防线）。 |
| **混合检索/rerank 你们怎么实现的？** | 这块在项目二（DeepRAG）：混合检索走稠密 + BM25（或 ColBERT/SPLADE）+ RRF 融合，rerank 用 cross-encoder 精排，还有 chunking、query 改写、评估方法论。agent 平台这边不重做，通过接口接项目二的检索器拿结果。 |

---

## 小结

1. **两层分工**：检索质量（混合/rerank/chunking/query/eval）归【项目二 DeepRAG】；平台集成（摆放/外部化/热度/隔离）归本章。两层正交，别混。

1. **平台 RAG 有意做成基线**：纯稠密向量，深度优化走项目二，平台只保证"基线能跑 + 集成层到位"。

1. **Lost in the Middle 是 agent 上下文问题**：U 形注意力，结果摆中间=白检索，最相关必须钉头尾（和检索质量正交）。

1. **大返回值外部化**：>50K 字符落临时文件 + 2KB 摘要（接 ch6）。

1. **按访问热度优化（学员痛点）**：访问日志 → 高频 chunk 预计算 embedding + 摘要 + 热区缓存 + 冷热分级。服务端独有，项目二不覆盖。

1. **多租户隔离**：检索带 tenantId filter，热度按租户统计。

> 下一章：多 Agent 编排——RAG 管"知识怎么进 agent"，多 Agent 管"**多个 Agent 怎么协作、什么时候才该上多 Agent**"（默认单 Agent 门禁 + 三种拓扑 + 主动 Agent）。
