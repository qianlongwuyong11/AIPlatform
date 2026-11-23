# BitGuide Agent Platform (Java)

<div align="center">

**循序渐进的 AI Agent 教学项目 — Java 实现**

从 MVP 到企业级，三阶段渐进式学习 Agent 开发

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![LangChain4j](https://img.shields.io/badge/LangChain4j-0.36.2-blue.svg)](https://github.com/langchain4j/langchain4j)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

</div>

---

## 项目简介

BitGuide Agent Platform 是一个**教学导向的 AI Agent 项目**，通过三个渐进阶段帮助开发者理解 Agent 开发：

- **Stage 1**：MVP Agent — LLM 对话 + 工具调用 + RAG 知识库检索
- **Stage 2**：通用平台 — 多 Agent + etcd 动态配置 + 热更新
- **Stage 3**：企业级服务 — Gateway 网关 + HTTP API + Redis 分布式 + Web 前端

## 三阶段学习路径

| | Stage 1 | Stage 2 | Stage 3 |
|---|---------|---------|---------|
| 定位 | MVP Agent | 通用平台 | 企业级服务 |
| Agent | 单一固定 Agent | 多 Agent + 动态注册 | 多 Agent + 分布式调度 |
| 配置 | YAML 静态配置 | etcd 动态配置 + 热更新 | etcd + Redis 分布式 |
| RAG | Milvus 向量检索 | 同 Stage 1 | 同 Stage 1 |
| 工具 | 内置工具 + MCP | 内置工具 + MCP | 内置工具 + MCP |
| 接口 | CLI 交互 | CLI 交互 | HTTP API + Web 前端 |
| 基础设施 | Ollama + Docker | + etcd | + Redis |

## 核心特性

| 特性 | Stage 1 | Stage 2 | Stage 3 |
|-----|:-------:|:-------:|:-------:|
| LLM 对话（ReAct 循环） | ✅ | ✅ | ✅ |
| 多轮对话记忆 | ✅ | ✅ | ✅ |
| 工具调用 | ✅ | ✅ | ✅ |
| RAG 知识库检索 | ✅ | ✅ | ✅ |
| MCP 工具协议 | ✅ | ✅ | ✅ |
| Skill 技能系统 | ✅ | ✅ | ✅ |
| 多 Agent 支持 | — | ✅ | ✅ |
| Agent 工厂 + 注册表 | — | ✅ | ✅ |
| etcd 动态配置 | — | ✅ | ✅ |
| 配置热更新（Watch） | — | ✅ | ✅ |
| Gateway 网关 | — | — | ✅ |
| 认证 / 限流 / 安全 | — | — | ✅ |
| HTTP API | — | — | ✅ |
| Web 聊天界面 | — | — | ✅ |
| Redis 分布式记忆 | — | — | ✅ |

## 项目结构

```
bitguide-agent-platform/
├── src/
│   ├── main/java/com/bitguide/       # 共享核心（Stage 2/3）
│   │   ├── agent/                    #   Agent 接口、BaseAgent、Factory、Registry
│   │   ├── config/                   #   配置 POJO + 加载器
│   │   │   └── dynamic/              #   etcd Watch → 动态配置管理
│   │   ├── gateway/                  #   网关：认证、限流、安全、路由、会话
│   │   ├── llm/                      #   LangChain4j LLM 封装
│   │   ├── mcp/                      #   MCP 协议客户端（stdio/http）
│   │   ├── memory/                   #   本地记忆 + Redis 分布式记忆
│   │   ├── rag/                      #   RAG：Embedding + Milvus + 分片
│   │   ├── skill/                    #   Skill 加载、管理、增强
│   │   ├── tool/                     #   内置工具 + MCP 桥接
│   │   └── log/                      #   ANSI 彩色终端日志
│   ├── stage1/java/                  # Stage 1 入口 + 配置
│   ├── stage2/java/                  # Stage 2 入口 + 配置
│   ├── stage3/java/                  # Stage 3 入口 + 配置
│   └── stage3/resources/public/      # Stage 3 Web 前端
├── mcp-servers/                      # MCP Server 实现（单文件 Java）
│   ├── parenting-mcp-server/         #   亲子教育工具（天气、年龄计算）
│   └── stock-mcp-server/             #   股票工具（行情、板块分析）
├── knowledge/                        # RAG 知识库文档
│   └── parenting_docs/               #   亲子教育文档（4 篇 Markdown）
├── skills/                           # Skill 技能定义
│   └── parenting-activity-advice/    #   亲子活动推荐技能
├── config/
│   └── etcd-examples/                #   etcd 推送示例 JSON
├── docker/                           # Docker Compose 基础设施
├── scripts/                          # 辅助脚本
│   ├── check-env.sh                  #   环境检查
│   └── etcd-config-cli.sh            #   etcd 配置管理
└── pom.xml                           # Maven 配置
```

## 快速开始

### 1. 环境准备

| 依赖 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | 运行环境 |
| Maven | 3.8+ | 构建工具 |
| Docker Desktop | 最新 | 基础设施（Milvus/etcd/Redis） |
| Ollama | 最新 | LLM 推理服务 |

```bash
# 一键检查环境
./scripts/check-env.sh --stage 1   # 检查 Stage 1 依赖
./scripts/check-env.sh --stage 3   # 全量检查
```

### 2. 启动基础设施

```bash
# 启动 Docker 服务（Milvus + etcd + Redis）
cd docker && docker compose up -d

# 启动 Ollama 并下载模型（新终端）
ollama serve
ollama pull qwen2.5
ollama pull nomic-embed-text   # RAG 向量化模型
```

### 3. 编译并运行

```bash
mvn clean compile

# Stage 1: MVP Agent（CLI 交互）
mvn exec:java -Dexec.mainClass="com.bitguide.stage1.Stage1App"

# Stage 2: 多 Agent + etcd 热更新（CLI 交互）
mvn exec:java -Dexec.mainClass="com.bitguide.stage2.Stage2App"

# Stage 3: 企业级 HTTP 服务
mvn exec:java -Dexec.mainClass="com.bitguide.stage3.Stage3App"
# 访问 http://localhost:8080
```

### 端口总览

| 端口 | 服务 | 说明 |
|------|------|------|
| 11434 | Ollama | LLM 推理服务 |
| 19530 | Milvus | 向量数据库（RAG） |
| 2379 | etcd | 动态配置中心（Stage 2+） |
| 6379 | Redis | 分布式缓存（Stage 3） |
| 8080 | 应用 | HTTP API + Web（Stage 3） |

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Java 17 |
| 构建 | Maven |
| LLM | LangChain4j 0.36.2（Ollama / OpenAI 兼容） |
| 向量数据库 | Milvus SDK 2.4.1 |
| 配置中心 | jetcd 0.7.6 |
| 分布式缓存 | Jedis 5.1.0 |
| Web | Spring Boot 3.2.0（Stage 3） |
| 工具协议 | MCP（stdio / http） |

## 配置文件

每个 Stage 有独立配置文件：

| Stage | 配置路径 |
|-------|---------|
| Stage 1 | `src/stage1/java/com/bitguide/stage1/config/stage1.yml` |
| Stage 2 | `src/stage2/java/com/bitguide/stage2/config/stage2.yml` |
| Stage 3 | `src/stage3/java/com/bitguide/stage3/config/stage3.yml` |

etcd 推送示例：`config/etcd-examples/agent-*.json`

## Go 版本

本项目另有 Go 语言实现：[bitguide-agent-platform-go](../bitguide-agent-platform-go)，功能与 Java 版一一对应。

## License

MIT
