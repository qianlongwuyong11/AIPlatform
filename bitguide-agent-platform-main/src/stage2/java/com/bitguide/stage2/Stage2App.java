package com.bitguide.stage2;

import com.bitguide.agent.Agent;
import com.bitguide.agent.AgentFactory;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.config.BitGuideConfig;
import com.bitguide.config.ConfigLoader;
import com.bitguide.config.KnowledgeBaseConfig;
import com.bitguide.config.ModelConfig;
import com.bitguide.config.dynamic.DynamicConfigManager;
import com.bitguide.config.dynamic.EtcdConfig;
import com.bitguide.config.dynamic.EtcdConfigWatcher;
import com.bitguide.llm.LLMGateway;
import com.bitguide.memory.ConversationMemory;
import com.bitguide.rag.RagService;
import lombok.extern.slf4j.Slf4j;

import java.util.Scanner;
import java.util.Set;

/**
 * Stage 2: 通用 Agent 平台演示
 * 
 * 基于动态配置的通用 Agent 平台，支持：
 * - 通过 etcd 动态加载 Agent 配置
 * - Agent 热加载（新增/修改/删除）
 * - 多种 Agent 类型切换
 * - 工具动态注册
 * 
 * 教学重点：
 * - 理解配置驱动的 Agent 架构
 * - 掌握 etcd 动态配置机制
 * - 学习 Agent 热加载实现
 */
@Slf4j
public class Stage2App {
    
    private static final String DEFAULT_CONFIG_PATH = "src/stage2/java/com/bitguide/stage2/config/stage2.yml";
    
    // 全局组件引用，用于优雅停机
    private static volatile DynamicConfigManager dynamicConfigManager;
    private static volatile AgentRegistry agentRegistry;
    private static volatile RagService ragService;
    private static volatile AgentFactory agentFactory;
    
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  Stage 2: 通用 Agent 平台 (动态配置)");
        System.out.println("========================================\n");
        
        // 解析配置文件路径
        String configPath = args.length > 0 ? args[0] : DEFAULT_CONFIG_PATH;
        
        // 加载配置
        BitGuideConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (Exception e) {
            log.error("加载配置失败: {}", e.getMessage());
            System.err.println("加载配置失败: " + e.getMessage());
            System.err.println("请确保配置文件存在: " + configPath);
            System.exit(1);
            return;
        }
        
        // 注册优雅停机钩子
        registerShutdownHook();
        
        try {
            // 1. 加载静态配置（模型、RAG等）
            System.out.println("[1] 加载静态配置文件...");
            System.out.println("    ✓ 配置加载成功");
            
            // 显示模型配置信息
            if (config.getModels() != null && !config.getModels().isEmpty()) {
                ModelConfig firstModel = config.getModels().values().iterator().next();
                System.out.println("    - 模型配置: " + firstModel.getModelName());
            }
            
            // 2. 初始化依赖组件
            System.out.println("\n[2] 初始化依赖组件...");
            LLMGateway llmGateway = new LLMGateway();
            ConversationMemory memory = new ConversationMemory();
            
            // 初始化 RAG 服务
            ragService = initRagService(config);
            
            System.out.println("    ✓ LLM 网关初始化完成");
            System.out.println("    ✓ 对话记忆初始化完成");
            
            // 3. 创建 Agent 工厂和注册表
            System.out.println("\n[3] 初始化 Agent 注册表...");
            agentFactory = new AgentFactory(config, llmGateway, ragService, memory);
            agentRegistry = new AgentRegistry(agentFactory);
            System.out.println("    ✓ Agent 注册表初始化完成");
            
            // 显示 MCP 连接状态
            if (agentFactory.getMcpClient() != null) {
                System.out.println("    ✓ MCP 已连接 Server: " + agentFactory.getMcpClient().getConnectedServers());
            } else {
                System.out.println("    - MCP 未启用或连接失败");
            }
            
            // 4. 配置 etcd 动态配置
            System.out.println("\n[4] 配置 etcd 动态配置...");
            EtcdConfig etcdConfig = config.getEtcd();
            if (etcdConfig == null) {
                etcdConfig = new EtcdConfig();
            }
            System.out.println("    - Etcd 启用: " + etcdConfig.isEnabled());
            if (etcdConfig.isEnabled()) {
                System.out.println("    - Etcd 地址: " + String.join(",", etcdConfig.getEndpoints()));
            }
            
            // 5. 初始化动态配置管理器
            System.out.println("\n[5] 初始化动态配置管理器...");
            EtcdConfigWatcher configWatcher = new EtcdConfigWatcher(etcdConfig);
            dynamicConfigManager = new DynamicConfigManager(configWatcher, agentRegistry, agentFactory);

            // 注入 RAG 服务（etcd 推送知识库配置时触发文档摄取）
            if (ragService != null) {
                dynamicConfigManager.setRagService(ragService);
            }

            // 注入技能管理器（etcd 推送技能配置时动态注册）
            if (agentFactory.getSkillManager() != null) {
                dynamicConfigManager.setSkillManager(agentFactory.getSkillManager());
            }

            try {
                dynamicConfigManager.init();
            } catch (Exception e) {
                log.warn("动态配置管理器初始化失败: {}，将进入等待模式", e.getMessage());
                System.err.println("\n⚠️  无法连接 Etcd: " + e.getMessage());
                System.err.println("   应用将继续运行，请通过 Etcd 动态添加 Agent 配置");
                System.err.println("   提示: 使用 scripts/etcd-config-cli.sh 脚本推送配置\n");
            }
            
            // 等待配置加载
            Thread.sleep(1000);
            
            // 6. 列出可用的 Agent
            System.out.println("\n[6] 可用的 Agent (从 etcd 动态加载):");
            Set<String> availableAgents = agentRegistry.getAvailableAgentIds();
            if (availableAgents.isEmpty()) {
                System.out.println("    ! 暂无可用 Agent，请通过 etcd 添加 Agent 配置");
                System.out.println("    示例: ./scripts/etcd-config-cli.sh put-agent parenting_advisor ./config/etcd-examples/agent-parenting_advisor.json");
            } else {
                for (String agentId : availableAgents) {
                    AgentRegistry.AgentInfo info = agentRegistry.getAllAgentInfo().get(agentId);
                    System.out.println("    - " + agentId + ": " + (info != null ? info.description() : ""));
                }
            }
            
            // 7. 开始 CLI
            startCLI();
            
        } catch (Exception e) {
            log.error("启动失败", e);
            System.err.println("启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * 注册优雅停机钩子
     */
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("收到停机信号，开始优雅停机...");
            System.out.println("\n\n正在关闭服务...");
            
            // 关闭动态配置管理器
            if (dynamicConfigManager != null) {
                try {
                    dynamicConfigManager.stop();
                    log.info("动态配置管理器已关闭");
                } catch (Exception e) {
                    log.warn("关闭动态配置管理器时发生异常", e);
                }
            }
            
            // 关闭 Agent 注册中心
            if (agentRegistry != null) {
                try {
                    agentRegistry.clear();
                    log.info("Agent 注册中心已清空");
                } catch (Exception e) {
                    log.warn("清空 Agent 注册中心时发生异常", e);
                }
            }
            
            // 关闭 RAG 服务
            if (ragService != null) {
                try {
                    ragService.close();
                    log.info("RAG 服务已关闭");
                } catch (Exception e) {
                    log.warn("关闭 RAG 服务时发生异常", e);
                }
            }
            
            // 关闭 MCP 客户端
            if (agentFactory != null && agentFactory.getMcpClient() != null) {
                try {
                    agentFactory.shutdownMcpClient();
                    log.info("MCP 客户端已关闭");
                } catch (Exception e) {
                    log.warn("关闭 MCP 客户端时发生异常", e);
                }
            }
            
            log.info("优雅停机完成");
            System.out.println("服务已关闭，再见！");
        }, "shutdown-hook"));
    }
    
    /**
     * 初始化 RAG 服务
     */
    private static RagService initRagService(BitGuideConfig config) {
        if (config.getRag() == null || !config.getRag().isEnabled()) {
            log.info("RAG 功能未启用");
            return null;
        }
        
        try {
            RagService ragService = new RagService(config.getRag());
            log.info("RAG 服务初始化成功");
            
            // 初始化知识库
            if (config.getKnowledgeBases() != null) {
                for (KnowledgeBaseConfig kbConfig : config.getKnowledgeBases().values()) {
                    try {
                        ragService.initKnowledgeBase(kbConfig);
                        log.info("知识库 [{}] 初始化成功", kbConfig.getId());
                    } catch (Exception e) {
                        log.warn("初始化知识库 [{}] 失败: {}", kbConfig.getId(), e.getMessage());
                    }
                }
            }
            
            System.out.println("    ✓ RAG 服务初始化完成");
            return ragService;
        } catch (Exception e) {
            log.warn("RAG 服务初始化失败，将禁用 RAG 功能: {}", e.getMessage());
            System.out.println("    ! RAG 服务初始化失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 启动 CLI
     */
    private static void startCLI() {
        Scanner scanner = new Scanner(System.in);
        String sessionId = "session-" + System.currentTimeMillis();
        String currentAgentId = null;
        Agent currentAgent = null;
        
        System.out.println("\n========================================");
        System.out.println("  开始对话");
        System.out.println("========================================");
        System.out.println("命令:");
        System.out.println("  /list       - 列出所有 Agent");
        System.out.println("  /switch id  - 切换 Agent");
        System.out.println("  /clear      - 清除对话历史");
        System.out.println("  /quit       - 退出");
        System.out.println("========================================\n");

        // 启动时自动选中第一个可用 Agent
        Set<String> initialAgents = agentRegistry.getAvailableAgentIds();
        if (!initialAgents.isEmpty()) {
            currentAgentId = initialAgents.iterator().next();
            currentAgent = agentRegistry.get(currentAgentId);
            System.out.println("✓ 已自动选择 Agent: " + currentAgentId);
            System.out.println("  (使用 /switch 切换, /list 查看所有)\n");
        }
        
        while (true) {
            // 显示当前 Agent
            String prompt = currentAgentId != null ? 
                    "你 [" + currentAgentId + "]> " : "你 (未选择 Agent)> ";
            System.out.print(prompt);
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                continue;
            }
            
            // 处理命令
            if (input.startsWith("/")) {
                String command = input.toLowerCase();
                
                if ("/quit".equals(command) || "/exit".equals(command)) {
                    System.out.println("\n再见！");
                    break;
                }
                
                if ("/list".equals(command)) {
                    listAgents();
                    continue;
                }
                
                if ("/clear".equals(command)) {
                    if (currentAgent != null) {
                        currentAgent.clearSession(sessionId);
                        System.out.println("对话历史已清除\n");
                    }
                    continue;
                }
                
                if (command.startsWith("/switch ")) {
                    String newAgentId = input.substring(8).trim();
                    Agent newAgent = agentRegistry.get(newAgentId);
                    if (newAgent != null) {
                        currentAgentId = newAgentId;
                        currentAgent = newAgent;
                        // 切换 Agent 时清除会话
                        sessionId = "session-" + System.currentTimeMillis();
                        System.out.println("已切换到 Agent: " + newAgentId + "\n");
                    } else {
                        System.out.println("Agent 不存在: " + newAgentId);
                        System.out.println("可用 Agent: " + agentRegistry.getAvailableAgentIds() + "\n");
                    }
                    continue;
                }
                
                System.out.println("未知命令: " + input);
                continue;
            }
            
            // 需要先选择 Agent
            if (currentAgent == null) {
                Set<String> available = agentRegistry.getAvailableAgentIds();
                if (available.isEmpty()) {
                    System.out.println("暂无可用 Agent，请先通过 etcd 添加 Agent 配置");
                    System.out.println("示例: ./scripts/etcd-config-cli.sh put-agent parenting_advisor ./config/etcd-examples/agent-parenting_advisor.json\n");
                    continue;
                }
                currentAgentId = available.iterator().next();
                currentAgent = agentRegistry.get(currentAgentId);
                System.out.println("自动选择 Agent: " + currentAgentId + "\n");
            }
            
            // 发送消息
            try {
                System.out.println("\n思考中...\n");
                String response = currentAgent.chat(input, sessionId);
                System.out.println("助手: " + response + "\n");
            } catch (Exception e) {
                log.error("对话异常", e);
                System.err.println("错误: " + e.getMessage() + "\n");
            }
        }
        
        scanner.close();
    }
    
    /**
     * 列出所有 Agent
     */
    private static void listAgents() {
        System.out.println("\n可用的 Agent:");
        Set<String> availableAgents = agentRegistry.getAvailableAgentIds();
        if (availableAgents.isEmpty()) {
            System.out.println("  (暂无)");
        } else {
            for (String agentId : availableAgents) {
                AgentRegistry.AgentInfo info = agentRegistry.getAllAgentInfo().get(agentId);
                System.out.println("  - " + agentId + ": " + (info != null ? info.description() : ""));
            }
        }
        System.out.println();
    }
}