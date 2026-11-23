package com.bitguide.agent;

import com.bitguide.config.AgentConfig;
import com.bitguide.config.BitGuideConfig;
import com.bitguide.config.ModelConfig;
import com.bitguide.config.dynamic.DynamicConfigManager;
import com.bitguide.llm.LLMGateway;
import com.bitguide.mcp.McpClient;
import com.bitguide.mcp.McpConfig;
import com.bitguide.mcp.McpTool;
import com.bitguide.memory.ConversationMemory;
import com.bitguide.rag.RagService;
import com.bitguide.tool.Tool;
import com.bitguide.tool.ToolManager;
import com.bitguide.skill.SkillEnhancer;
import com.bitguide.skill.SkillManager;
import com.bitguide.tool.tools.CalculatorTool;
import com.bitguide.tool.tools.DateTimeTool;
import com.bitguide.tool.tools.WebSearchTool;
import com.bitguide.tool.tools.DataAnalyzerTool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 工厂
 * 根据配置动态装配 Agent 实例
 * 支持可扩展性：新场景只需配置即可接入
 */
@Slf4j
public class AgentFactory {
    
    private final BitGuideConfig config;
    private final LLMGateway llmGateway;
    private final RagService ragService;
    private final ConversationMemory sharedMemory;
    private final Map<String, Agent> agentCache = new ConcurrentHashMap<>();
    
    private DynamicConfigManager dynamicConfigManager;
    private McpClient mcpClient;
    // 每个 Agent 的内联 MCP 客户端（用于热替换/注销时关闭连接）
    private final Map<String, McpClient> inlineMcpClients = new ConcurrentHashMap<>();
    private final Map<String, Tool> builtinTools = new HashMap<>();
    private SkillManager skillManager;
    
    public AgentFactory(BitGuideConfig config, LLMGateway llmGateway, 
                       RagService ragService, ConversationMemory memory) {
        this.config = config;
        this.llmGateway = llmGateway;
        this.ragService = ragService;
        this.sharedMemory = memory;
        registerBuiltinTools();
        initMcpClient();
        initSkillManager();
    }
    
    public void setDynamicConfigManager(DynamicConfigManager dynamicConfigManager) {
        this.dynamicConfigManager = dynamicConfigManager;
    }
    
    /**
     * 注册内置工具（只注册通用、与业务领域无关的工具）
     * 业务领域工具全部走 MCP 协议，由 Agent 配置中的 mcpServers 动态加载
     */
    private void registerBuiltinTools() {
        registerTool(new CalculatorTool());
        registerTool(new DateTimeTool());
        registerTool(new WebSearchTool());
        registerTool(new DataAnalyzerTool());
        log.info("内置工具注册完成: {}（业务工具已迁移至 MCP）", builtinTools.keySet());
    }
    
    public void registerTool(Tool tool) {
        builtinTools.put(tool.getName(), tool);
    }
    
    private void initMcpClient() {
        McpConfig mcpConfig = config.getMcp();
        if (mcpConfig == null || !Boolean.TRUE.equals(mcpConfig.getEnabled())) {
            log.info("MCP 未启用");
            return;
        }
        try {
            mcpClient = new McpClient(mcpConfig);
            mcpClient.connectAll();
            log.info("MCP 客户端初始化完成，已连接 Server: {}", mcpClient.getConnectedServers());
        } catch (Exception e) {
            log.error("MCP 客户端初始化失败: {}", e.getMessage(), e);
            mcpClient = null;
        }
    }
    
    public McpClient getMcpClient() {
        return mcpClient;
    }
    
    public void shutdownMcpClient() {
        if (mcpClient != null) {
            mcpClient.disconnectAll();
            log.info("MCP 客户端已关闭");
        }
    }

    /**
     * 销毁 Agent 关联的资源（内联 MCP 连接等）
     * 在 Agent 热替换或注销时由 AgentRegistry 调用
     */
    public void destroyAgent(String agentId) {
        McpClient client = inlineMcpClients.remove(agentId);
        if (client != null) {
            client.disconnectAll();
            log.info("Agent [{}] 内联 MCP 连接已关闭", agentId);
        }
        agentCache.remove(agentId);
    }
    
    private void initSkillManager() {
        BitGuideConfig.SkillsConfig skillsConfig = config.getSkills();
        if (skillsConfig == null || !skillsConfig.isEnabled()) {
            log.info("Skills 未启用");
            return;
        }
        try {
            String skillsDir = skillsConfig.getDirectory() != null 
                    ? skillsConfig.getDirectory() : "skills";
            skillManager = new SkillManager(skillsDir);
            skillManager.loadFromDirectory();
            SkillEnhancer enhancer = new SkillEnhancer(builtinTools);
            skillManager.setEnhancer(enhancer);
            if (config.getModels() != null && !config.getModels().isEmpty()) {
                ModelConfig firstModelConfig = config.getModels().values().iterator().next();
                ChatLanguageModel routingModel = llmGateway.getOrCreateModel(firstModelConfig);
                skillManager.setModel(routingModel);
                log.info("Skill LLM 路由模型已设置: {}", firstModelConfig.getModelName());
            }
            if (skillsConfig.isHotReload()) {
                skillManager.setHotReloadEnabled(true);
            }
            log.info("Skills 初始化完成，已加载技能: {}", skillManager.getSkillNames());
        } catch (Exception e) {
            log.warn("Skills 初始化失败: {}", e.getMessage());
            skillManager = null;
        }
    }
    
    public SkillManager getSkillManager() {
        return skillManager;
    }
    
    public Agent getOrCreateAgent(String agentId) {
        return agentCache.computeIfAbsent(agentId, this::createAgentById);
    }
    
    /**
     * 根据 AgentConfig 创建 Agent
     */
    public Agent createAgent(AgentConfig config) {
        log.info("创建 Agent: {}", config.getId());
        
        // 1. 装配 LLM
        ChatLanguageModel model = createModel(config);
        
        // 2. 装配 Prompt
        String systemPrompt = getSystemPrompt(config);
        
        // 3. 装配 ToolManager
        ToolManager toolManager = createToolManager(config);
        
        // 4. 判断是否启用 Skill（只有配置了 skillRefs 的 Agent 才启用）
        SkillManager agentSkillManager = null;
        if (skillManager != null && config.getSkillRefs() != null 
                && !config.getSkillRefs().isEmpty()) {
            agentSkillManager = skillManager;
            log.info("Agent [{}] 启用 Skills: {}", config.getId(), config.getSkillRefs());
        }
        
        // 5. 构建 Agent
        Agent agent = new BaseAgent.Builder()
                .id(config.getId())
                .name(config.getName())
                .model(model)
                .systemPrompt(systemPrompt)
                .toolManager(toolManager)
                .knowledgeBase(config.getKnowledgeBase())
                .ragService(ragService)
                .memory(sharedMemory)
                .skillManager(agentSkillManager)
                .build();

        // 6. 健康检查（非阻塞）
        healthCheck(config);

        return agent;
    }
    
    private Agent createAgentById(String agentId) {
        log.info("创建 Agent: {}", agentId);
        AgentConfig agentConfig = config.getAgents().get(agentId);
        if (agentConfig == null) {
            throw new RuntimeException("Agent 配置不存在: " + agentId);
        }
        return createAgent(agentConfig);
    }
    
    private ChatLanguageModel createModel(AgentConfig agentConfig) {
        String modelRef = agentConfig.getModelRef();
        ModelConfig modelConfig = null;
        if (dynamicConfigManager != null) {
            modelConfig = convertToModelConfig(
                dynamicConfigManager.getModelConfigs().get(modelRef)
            );
        }
        if (modelConfig == null && config != null && config.getModels() != null) {
            modelConfig = config.getModels().get(modelRef);
        }
        if (modelConfig == null) {
            throw new RuntimeException("Model 配置不存在: " + modelRef);
        }
        return llmGateway.getOrCreateModel(modelConfig);
    }
    
    private String getSystemPrompt(AgentConfig agentConfig) {
        String systemPrompt = agentConfig.getSystemPrompt();
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            log.warn("Agent [{}] 未配置 systemPrompt", agentConfig.getId());
            return "";
        }
        return systemPrompt;
    }
    
    private ModelConfig convertToModelConfig(DynamicConfigManager.ModelConfigWrapper wrapper) {
        if (wrapper == null) return null;
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setProvider(wrapper.provider);
        modelConfig.setApiKey(wrapper.apiKey);
        modelConfig.setBaseUrl(wrapper.baseUrl);
        modelConfig.setModelName(wrapper.modelName);
        modelConfig.setTemperature(wrapper.temperature);
        modelConfig.setMaxTokens(wrapper.maxTokens);
        return modelConfig;
    }
    
    private ToolManager createToolManager(AgentConfig agentConfig) {
        ToolManager toolManager = new ToolManager();
        
        // 1. 加载内置工具（按 toolRefs）
        List<String> toolRefs = agentConfig.getToolRefs();
        if (toolRefs != null && !toolRefs.isEmpty()) {
            for (String toolRef : toolRefs) {
                Tool tool = builtinTools.get(toolRef);
                if (tool != null) {
                    toolManager.registerTool(tool);
                    log.debug("为 Agent [{}] 注册内置工具: {}", agentConfig.getId(), toolRef);
                } else {
                    log.warn("工具不存在: {}", toolRef);
                }
            }
        }
        
        // 2. 加载内联 MCP 工具（按 mcpServers，优先级高于 mcpServerRefs）
        List<McpConfig.ServerConfig> inlineMcpServers = agentConfig.getMcpServers();
        if (inlineMcpServers != null && !inlineMcpServers.isEmpty()) {
            connectInlineMcpServers(agentConfig.getId(), inlineMcpServers, toolManager);
        }
        
        // 3. 加载全局 MCP 工具（按 mcpServerRefs，向后兼容）
        List<String> mcpServerRefs = agentConfig.getMcpServerRefs();
        if (mcpServerRefs != null && !mcpServerRefs.isEmpty() && mcpClient != null) {
            // 只有在没有内联 mcpServers 的情况下才加载全局 MCP（避免重复）
            // 或者 mcpServerRefs 引用了内联中没有的 server
            for (McpTool mcpTool : mcpClient.getAllTools()) {
                if (mcpServerRefs.contains(mcpTool.getServerName())) {
                    if (!toolManager.hasTool(mcpTool.getName())) {
                        toolManager.registerTool(mcpTool);
                        log.debug("为 Agent [{}] 注册全局 MCP 工具: {} (from {})", 
                                agentConfig.getId(), mcpTool.getName(), mcpTool.getServerName());
                    }
                }
            }
        }
        
        return toolManager;
    }
    
    /**
     * 连接 Agent 配置中内联定义的 MCP Server 并加载工具
     * 这是动态工具扩展的核心：推送配置时直接包含 MCP Server 连接信息
     */
    private void connectInlineMcpServers(String agentId, List<McpConfig.ServerConfig> servers,
                                          ToolManager toolManager) {
        // 为每个 Agent 创建独立的 McpClient（隔离不同 Agent 的 MCP 连接）
        McpConfig inlineConfig = new McpConfig();
        inlineConfig.setEnabled(true);
        inlineConfig.setServers(servers);

        try {
            McpClient agentMcpClient = new McpClient(inlineConfig);
            agentMcpClient.connectAll();

            // 记录到 inlineMcpClients，热替换/注销时关闭连接
            inlineMcpClients.put(agentId, agentMcpClient);

            // 获取所有工具并注册
            List<McpTool> tools = agentMcpClient.getAllTools();
            for (McpTool tool : tools) {
                toolManager.registerTool(tool);
                log.info("为 Agent [{}] 注册内联 MCP 工具: {} (from {})",
                        agentId, tool.getName(), tool.getServerName());
            }

            log.info("Agent [{}] 内联 MCP 连接完成, server数={}, 工具数={}",
                    agentId, servers.size(), tools.size());
        } catch (Exception e) {
            log.error("Agent [{}] 内联 MCP 连接失败: {}", agentId, e.getMessage(), e);
        }
    }
    
    public Set<String> getAvailableAgentIds() {
        return config.getAgents().keySet();
    }
    
    public Set<String> getAvailableToolNames() {
        return builtinTools.keySet();
    }

    /**
     * 健康检查报告
     */
    public static class AgentHealthReport {
        private final String agentId;
        private final Map<String, String> mcpStatus = new HashMap<>();
        private boolean ragAvailable;
        private final Map<String, Boolean> skillsFound = new HashMap<>();
        private final List<String> warnings = new ArrayList<>();

        public AgentHealthReport(String agentId) {
            this.agentId = agentId;
        }

        public void addWarning(String warning) { warnings.add(warning); }
        public void setMcpStatus(String server, String status) { mcpStatus.put(server, status); }
        public void setRagAvailable(boolean available) { ragAvailable = available; }
        public void setSkillFound(String skillId, boolean found) { skillsFound.put(skillId, found); }

        public String getAgentId() { return agentId; }
        public List<String> getWarnings() { return warnings; }
    }

    /**
     * 对 Agent 配置进行健康检查（非阻塞，只记录警告）
     */
    public AgentHealthReport healthCheck(AgentConfig config) {
        AgentHealthReport report = new AgentHealthReport(config.getId());

        // 检查 MCP 连通性
        if (config.getMcpServers() != null) {
            for (McpConfig.ServerConfig server : config.getMcpServers()) {
                report.setMcpStatus(server.getName(), "configured");
            }
        }

        // 检查 RAG 可用性
        if (config.getKnowledgeBase() != null && !config.getKnowledgeBase().isEmpty()) {
            report.setRagAvailable(ragService != null);
            if (ragService == null) {
                report.addWarning("RAG 未初始化，知识库 " + config.getKnowledgeBase() + " 不可用");
            }
        }

        // 检查 Skill 引用是否有效
        if (config.getSkillRefs() != null && skillManager != null) {
            for (String skillRef : config.getSkillRefs()) {
                boolean found = skillManager.hasSkill(skillRef);
                report.setSkillFound(skillRef, found);
                if (!found) {
                    report.addWarning("Skill '" + skillRef + "' 未找到");
                }
            }
        }

        // 输出警告日志
        for (String w : report.getWarnings()) {
            log.warn("[HealthCheck] Agent [{}]: {}", config.getId(), w);
        }
        if (report.getWarnings().isEmpty()) {
            log.info("[HealthCheck] Agent [{}] 健康检查通过", config.getId());
        }

        return report;
    }
    
    public void clearCache() {
        agentCache.clear();
        log.info("Agent 缓存已清除");
    }
}