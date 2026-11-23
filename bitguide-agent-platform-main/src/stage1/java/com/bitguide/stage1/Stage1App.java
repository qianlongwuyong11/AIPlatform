package com.bitguide.stage1;

import com.bitguide.agent.Agent;
import com.bitguide.agent.BaseAgent;
import com.bitguide.config.ModelConfig;
import com.bitguide.config.RagConfig;
import com.bitguide.llm.LLMGateway;
import com.bitguide.mcp.McpClient;
import com.bitguide.mcp.McpConfig;
import com.bitguide.mcp.McpTool;
import com.bitguide.memory.ConversationMemory;
import com.bitguide.rag.RagService;
import com.bitguide.skill.SkillEnhancer;
import com.bitguide.skill.SkillManager;
import com.bitguide.stage1.config.Stage1Config;
import com.bitguide.tool.ToolManager;
import com.bitguide.tool.tools.CalculatorTool;
import com.bitguide.tool.tools.DateTimeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Scanner;

/**
 * Stage 1: MVP - 固定领域亲子教育顾问 Agent
 * 
 * 这是一个亲子教育顾问智能体的入门示例，展示 Agent 的核心组件组装：
 * - Stage1Config: 简化的配置加载
 * - LLMGateway: LLM 调用能力
 * - ConversationMemory: 多轮对话记忆
 * - ToolManager: 工具调用
 * - RagService: RAG 知识检索（可选）
 * - BaseAgent.Builder: Agent 组装
 * 
 * 适用人群：后端0基础或有一定后端基础，没有Agent开发经验的学员
 * 
 * 教学重点：
 * - 理解 Agent 的核心组件
 * - 掌握 Builder 模式组装 Agent
 * - 学习对话循环的基本实现
 * - 了解 RAG 知识检索的集成方式
 */
public class Stage1App {
    
    // 默认配置文件路径（相对于 classpath）
    private static final String DEFAULT_CONFIG_PATH = "stage1.yml";
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  Stage 1: 亲子教育顾问 Agent");
        System.out.println("========================================\n");
        
        try {
            // 1. 加载配置
            System.out.println("[1] 加载配置文件...");
            Stage1Config config = loadConfig(args);
            Stage1Config.AgentConfig agentConfig = config.getAgent();
            Stage1Config.ModelConfig modelConfig = config.getModel();
            
            System.out.println("    ✓ 配置加载成功");
            System.out.println("    - Agent: " + agentConfig.getName());
            System.out.println("    - 模型: " + modelConfig.getModelName());
            
            // 2. 初始化 LLM
            System.out.println("\n[2] 初始化 LLM...");
            LLMGateway llmGateway = new LLMGateway();
            
            // 将 Stage1Config.ModelConfig 转换为 ModelConfig
            ModelConfig mc = new ModelConfig();
            mc.setProvider(modelConfig.getProvider());
            mc.setApiKey(modelConfig.getApiKey());
            mc.setBaseUrl(modelConfig.getBaseUrl());
            mc.setModelName(modelConfig.getModelName());
            mc.setTemperature(modelConfig.getTemperature() != null ? modelConfig.getTemperature() : 0.7);
            mc.setMaxTokens(modelConfig.getMaxTokens() != null ? modelConfig.getMaxTokens() : 2000);
            
            ChatLanguageModel model = llmGateway.getOrCreateModel(mc);
            System.out.println("    ✓ LLM 初始化完成");
            
            // 3. 初始化对话记忆
            System.out.println("\n[3] 初始化对话记忆...");
            int maxHistory = config.getMemory() != null && config.getMemory().getMaxHistoryLength() != null
                    ? config.getMemory().getMaxHistoryLength()
                    : 50;
            ConversationMemory memory = new ConversationMemory();
            System.out.println("    ✓ 对话记忆初始化完成 (maxHistory=" + maxHistory + ")");
            
            // 4. 注册工具
            System.out.println("\n[4] 注册工具...");
            ToolManager toolManager = new ToolManager();
            if (agentConfig.getTools() != null) {
                for (String toolName : agentConfig.getTools()) {
                    switch (toolName) {
                        case "calculator":
                            toolManager.registerTool(new CalculatorTool());
                            break;
                        case "datetime":
                            toolManager.registerTool(new DateTimeTool());
                            break;
                        default:
                            System.out.println("    ! 未知工具: " + toolName);
                    }
                }
            }
            System.out.println("    ✓ 工具注册完成: " + toolManager.getToolNames());
            
            // 5. 初始化 RAG 服务（可选）
            System.out.println("\n[5] 初始化 RAG 服务...");
            RagService ragService = null;
            String knowledgeBase = agentConfig.getKnowledgeBase();
            
            if (config.getRag() != null && Boolean.TRUE.equals(config.getRag().getEnabled())) {
                try {
                    // 将 Stage1Config.RagConfig 转换为 RagConfig
                    RagConfig ragConfig = convertRagConfig(config.getRag());
                    ragService = new RagService(ragConfig);
                    System.out.println("    ✓ RAG 服务初始化完成");

                    // 加载知识库文档
                    if (knowledgeBase != null && !knowledgeBase.isEmpty()) {
                        String docDir = knowledgeBase.contains("/") ? knowledgeBase : "knowledge/" + knowledgeBase;
                        // 集合名只允许字母、数字、下划线，路径中的 / 替换为 _
                        String kbId = knowledgeBase.replace("/", "_").replace("-", "_");
                        System.out.println("    - 加载知识库: " + docDir + " (集合: " + kbId + ")");
                        ragService.loadDocumentsFromPath(kbId, docDir, java.util.List.of(".md", ".txt"));
                        System.out.println("    ✓ 知识库加载完成: " + knowledgeBase);
                        // 后续 Agent 查询也使用 kbId
                        knowledgeBase = kbId;
                    }
                } catch (Exception e) {
                    System.out.println("    ! RAG 服务初始化失败: " + e.getMessage());
                    System.out.println("    ! 将在不使用 RAG 的情况下运行");
                    ragService = null;
                }
            } else {
                System.out.println("    - RAG 未启用");
            }
            
            // 6. 初始化 MCP 客户端（可选）
            System.out.println("\n[6] 初始化 MCP 服务...");
            McpClient mcpClient = null;
            
            if (config.getMcp() != null && Boolean.TRUE.equals(config.getMcp().getEnabled())) {
                try {
                    // 将 Stage1Config.McpConfig 转换为 McpConfig
                    McpConfig mcpConfig = convertMcpConfig(config.getMcp());
                    mcpClient = new McpClient(mcpConfig);
                    
                    // 连接所有 MCP Server
                    mcpClient.connectAll();
                    
                    // 将 MCP 工具注册到 ToolManager
                    for (McpTool mcpTool : mcpClient.getAllTools()) {
                        toolManager.registerTool(mcpTool);
                    }
                    
                    System.out.println("    ✓ MCP 服务初始化完成");
                    System.out.println("    - 已连接 Server: " + mcpClient.getConnectedServers());
                } catch (Exception e) {
                    System.out.println("    ! MCP 服务初始化失败: " + e.getMessage());
                    System.out.println("    ! 将在不使用 MCP 的情况下运行");
                    mcpClient = null;
                }
            } else {
                System.out.println("    - MCP 未启用");
            }
            
            // 7. 初始化 Skills（可选，能力增强模式）
            System.out.println("\n[7] 初始化 Skills...");
            SkillManager skillManager = null;
            
            if (config.getSkills() != null && Boolean.TRUE.equals(config.getSkills().getEnabled())) {
                try {
                    String skillsDir = config.getSkills().getDirectory() != null 
                            ? config.getSkills().getDirectory() 
                            : "skills";
                    
                    skillManager = new SkillManager(skillsDir);
                    skillManager.loadFromDirectory();
                    
                    // 设置 LLM 模型用于智能路由
                    skillManager.setModel(model);
                    
                    // 设置热重载
                    if (Boolean.TRUE.equals(config.getSkills().getHotReload())) {
                        skillManager.setHotReloadEnabled(true);
                    }
                    
                    // 创建技能增强器（Skill Prompt + Agent ReACT 引擎）
                    SkillEnhancer skillEnhancer = new SkillEnhancer(toolManager.getToolMap());
                    skillManager.setEnhancer(skillEnhancer);
                    
                    System.out.println("    ✓ Skills 初始化完成");
                    System.out.println("    - 已加载技能: " + skillManager.getSkillNames());
                } catch (Exception e) {
                    System.out.println("    ! Skills 初始化失败: " + e.getMessage());
                    System.out.println("    ! 将在不使用 Skills 的情况下运行");
                    skillManager = null;
                }
            } else {
                System.out.println("    - Skills 未启用");
            }
            
            // 8. 使用 Builder 模式创建 Agent
            System.out.println("\n[8] 创建 Agent...");
            BaseAgent.Builder agentBuilder = new BaseAgent.Builder()
                    .id(agentConfig.getId())
                    .name(agentConfig.getName())
                    .model(model)
                    .systemPrompt(agentConfig.getSystemPrompt())
                    .toolManager(toolManager)
                    .memory(memory);
            
            // 如果 RAG 可用，添加 RAG 相关配置
            if (ragService != null && knowledgeBase != null) {
                agentBuilder.ragService(ragService)
                           .knowledgeBase(knowledgeBase);
            }
            
            Agent agent = agentBuilder.build();
            System.out.println("    ✓ Agent 创建成功");
            
            // 9. 开始对话循环
            System.out.println("\n========================================");
            System.out.println("  开始对话 (输入 'quit' 退出, 'clear' 清除历史, 'skills' 查看技能)");
            System.out.println("========================================\n");
            
            // 显示可用技能
            if (skillManager != null && !skillManager.getSkillNames().isEmpty()) {
                System.out.println("可用技能: " + skillManager.getSkillNames() + "\n");
            }
            
            String sessionId = "session-" + System.currentTimeMillis();
            Scanner scanner = new Scanner(System.in);
            
            while (true) {
                System.out.print("你: ");
                String input = scanner.nextLine().trim();
                
                if (input.isEmpty()) {
                    continue;
                }
                
                if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                    System.out.println("\n再见！祝您和孩子度过美好时光！");
                    break;
                }
                
                if ("clear".equalsIgnoreCase(input)) {
                    agent.clearSession(sessionId);
                    System.out.println("对话历史已清除\n");
                    continue;
                }
                
                // 查看技能列表
                if ("skills".equalsIgnoreCase(input)) {
                    if (skillManager != null) {
                        System.out.println("\n" + skillManager.getSkillDescriptions());
                    } else {
                        System.out.println("Skills 未启用\n");
                    }
                    continue;
                }
                
                // 尝试匹配并执行技能（能力增强模式：Skill Prompt + Agent ReACT 引擎）
                if (skillManager != null) {
                    try {
                        SkillManager.MatchResult matchResult = skillManager.matchAndExecute(agent, input, sessionId);
                        if (matchResult.isMatched()) {
                            if (matchResult.isSuccess()) {
                                System.out.println("\n助手: " + matchResult.getResponse() + "\n");
                            } else {
                                System.out.println("技能执行失败: " + matchResult.getError() + "\n");
                            }
                            continue;
                        }
                        // 未匹配到技能，继续走普通对话流程
                    } catch (Exception e) {
                        System.err.println("技能匹配错误: " + e.getMessage());
                    }
                }
                
                try {
                    System.out.println("\n思考中...\n");
                    String response = agent.chat(input, sessionId);
                    System.out.println("助手: " + response + "\n");
                } catch (Exception e) {
                    System.err.println("错误: " + e.getMessage());
                }
            }
            
            scanner.close();
            
            // 关闭 RAG 服务
            if (ragService != null) {
                ragService.close();
            }
            
            // 关闭 MCP 客户端
            if (mcpClient != null) {
                mcpClient.disconnectAll();
            }
            
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 将 Stage1Config.McpConfig 转换为 McpConfig
     */
    private static McpConfig convertMcpConfig(Stage1Config.McpConfig src) {
        McpConfig dst = new McpConfig();
        dst.setEnabled(src.getEnabled() != null ? src.getEnabled() : true);
        
        if (src.getServers() != null) {
            java.util.List<McpConfig.ServerConfig> servers = new java.util.ArrayList<>();
            for (Stage1Config.ServerConfig sc : src.getServers()) {
                McpConfig.ServerConfig serverConfig = new McpConfig.ServerConfig();
                serverConfig.setName(sc.getName());
                serverConfig.setTransport(sc.getTransport());
                serverConfig.setCommand(sc.getCommand());
                serverConfig.setArgs(sc.getArgs());
                serverConfig.setEnv(sc.getEnv());
                serverConfig.setUrl(sc.getUrl());
                serverConfig.setHeaders(sc.getHeaders());
                serverConfig.setTimeout(sc.getTimeout());
                servers.add(serverConfig);
            }
            dst.setServers(servers);
        }
        
        return dst;
    }
    
    /**
     * 将 Stage1Config.RagConfig 转换为 RagConfig
     */
    private static RagConfig convertRagConfig(Stage1Config.RagConfig src) {
        RagConfig dst = new RagConfig();
        dst.setEnabled(src.getEnabled() != null ? src.getEnabled() : true);
        
        // 转换 VectorStore 配置
        if (src.getVectorStore() != null) {
            RagConfig.VectorStoreConfig vs = new RagConfig.VectorStoreConfig();
            vs.setType(src.getVectorStore().getType());
            vs.setHost(src.getVectorStore().getHost());
            vs.setPort(src.getVectorStore().getPort());
            vs.setCollectionPrefix(src.getVectorStore().getCollectionPrefix());
            dst.setVectorStore(vs);
        }
        
        // 转换 Embedding 配置
        if (src.getEmbedding() != null) {
            RagConfig.EmbeddingConfig ec = new RagConfig.EmbeddingConfig();
            ec.setProvider(src.getEmbedding().getProvider());
            ec.setBaseUrl(src.getEmbedding().getBaseUrl());
            ec.setModelName(src.getEmbedding().getModelName());
            ec.setDimension(src.getEmbedding().getDimension());
            dst.setEmbedding(ec);
        }
        
        // 转换 Splitter 配置
        if (src.getSplitter() != null) {
            RagConfig.SplitterConfig sc = new RagConfig.SplitterConfig();
            sc.setMaxSegmentSize(src.getSplitter().getMaxSegmentSize());
            sc.setOverlap(src.getSplitter().getOverlap());
            dst.setSplitter(sc);
        }
        
        // 转换 Retrieval 配置
        if (src.getRetrieval() != null) {
            RagConfig.RetrievalConfig rc = new RagConfig.RetrievalConfig();
            rc.setTopK(src.getRetrieval().getTopK());
            rc.setScoreThreshold(src.getRetrieval().getScoreThreshold());
            dst.setRetrieval(rc);
        }
        
        return dst;
    }
    
    /**
     * 加载配置文件
     * 优先级：命令行参数 > classpath > 文件路径
     */
    private static Stage1Config loadConfig(String[] args) throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        
        // 1. 尝试从命令行参数加载
        if (args.length > 0) {
            File configFile = new File(args[0]);
            if (configFile.exists()) {
                return yamlMapper.readValue(configFile, Stage1Config.class);
            }
        }
        
        // 2. 尝试从 classpath 加载
        InputStream classpathStream = Stage1App.class
                .getClassLoader()
                .getResourceAsStream(DEFAULT_CONFIG_PATH);
        if (classpathStream != null) {
            return yamlMapper.readValue(classpathStream, Stage1Config.class);
        }
        
        // 3. 尝试从文件系统加载（相对于项目根目录）
        File fileConfig = new File("src/stage1/java/com/bitguide/stage1/config/" + DEFAULT_CONFIG_PATH);
        if (fileConfig.exists()) {
            return yamlMapper.readValue(fileConfig, Stage1Config.class);
        }
        
        throw new RuntimeException("配置文件未找到: " + DEFAULT_CONFIG_PATH + 
                "\n请确保配置文件存在于 classpath 或 src/stage1/java/com/bitguide/stage1/config/ 目录下");
    }
}