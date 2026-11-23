package com.bitguide.config.dynamic;

import com.bitguide.log.ConsoleLog;
import com.bitguide.agent.AgentFactory;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.config.AgentConfig;
import com.bitguide.config.KnowledgeBaseConfig;
import com.bitguide.rag.RagService;
import com.bitguide.skill.Skill;
import com.bitguide.skill.SkillManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态配置管理器
 * 整合 Etcd 配置监听和 Agent 注册中心
 */
@Slf4j
public class DynamicConfigManager implements ConfigChangeListener {
    
    private final EtcdConfigWatcher configWatcher;
    private final AgentRegistry agentRegistry;
    private final AgentFactory agentFactory;
    private final ObjectMapper objectMapper;

    private SkillManager skillManager;
    private RagService ragService;
    
    /**
     * 模型配置缓存
     */
    private final Map<String, ModelConfigWrapper> modelConfigs = new ConcurrentHashMap<>();
    
    /**
     * 知识库配置缓存
     */
    private final Map<String, KnowledgeBaseConfigWrapper> knowledgeBaseConfigs = new ConcurrentHashMap<>();
    
    public DynamicConfigManager(EtcdConfigWatcher configWatcher, 
                                AgentRegistry agentRegistry,
                                AgentFactory agentFactory) {
        this.configWatcher = configWatcher;
        this.agentRegistry = agentRegistry;
        this.agentFactory = agentFactory;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 设置技能管理器（用于 etcd 动态注册技能）
     */
    public void setSkillManager(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    /**
     * 设置 RAG 服务（用于 etcd 动态加载知识库）
     */
    public void setRagService(RagService ragService) {
        this.ragService = ragService;
    }
    
    /**
     * 初始化动态配置管理器
     */
    public void init() {
        // 注册自身为配置变更监听器
        configWatcher.addListener(this);
        
        // 启动配置监听
        configWatcher.start();
        
        ConsoleLog.info("[Dynamic]", "动态配置管理器初始化完成");
    }
    
    /**
     * 处理配置变更事件
     */
    @Override
    public void onConfigChange(ConfigEvent event) {
        ConsoleLog.dynamicConfigEvent(event.getType().name(), event.getConfigType(), event.getConfigId());
        
        try {
            // 根据配置类型分发处理
            if (event.isAgentConfig()) {
                handleAgentConfigChange(event);
            } else if (event.isModelConfig()) {
                handleModelConfigChange(event);
            } else if (event.isKnowledgeBaseConfig()) {
                handleKnowledgeBaseConfigChange(event);
            } else if (event.isSkillConfig()) {
                handleSkillConfigChange(event);
            } else {
                log.debug("未知的配置类型: {}", event.getConfigType());
            }
        } catch (Exception e) {
            log.error("处理配置变更失败: {}", event, e);
        }
    }
    
    /**
     * 处理 Agent 配置变更
     */
    private void handleAgentConfigChange(ConfigEvent event) {
        String agentId = event.getConfigId();
        
        switch (event.getType()) {
            case PUT:
                // 新增 Agent
                AgentConfig config = parseAgentConfig(event.getNewValue());
                if (config != null) {
                    agentRegistry.register(agentId, config);
                }
                break;
                
            case UPDATE:
                // 更新 Agent
                AgentConfig newConfig = parseAgentConfig(event.getNewValue());
                if (newConfig != null) {
                    agentRegistry.update(agentId, newConfig);
                }
                break;
                
            case DELETE:
                // 删除 Agent
                agentRegistry.unregister(agentId);
                break;
        }
    }
    
    /**
     * 处理模型配置变更
     */
    private void handleModelConfigChange(ConfigEvent event) {
        String modelId = event.getConfigId();
        
        switch (event.getType()) {
            case PUT:
            case UPDATE:
                ModelConfigWrapper modelConfig = parseModelConfig(event.getNewValue());
                if (modelConfig != null) {
                    modelConfigs.put(modelId, modelConfig);
                    log.info("模型配置 [{}] 已更新", modelId);
                }
                break;
                
            case DELETE:
                modelConfigs.remove(modelId);
                log.info("模型配置 [{}] 已删除", modelId);
                break;
        }
    }
    
    /**
     * 处理知识库配置变更（增强：动态触发文档摄取和删除）
     */
    private void handleKnowledgeBaseConfigChange(ConfigEvent event) {
        String kbId = event.getConfigId();

        switch (event.getType()) {
            case PUT:
            case UPDATE:
                KnowledgeBaseConfigWrapper kbConfig = parseKnowledgeBaseConfig(event.getNewValue());
                if (kbConfig != null) {
                    knowledgeBaseConfigs.put(kbId, kbConfig);
                    ConsoleLog.info("[Dynamic]", "知识库配置 [%s] 已更新", kbId);

                    // 动态触发文档摄取
                    if (ragService != null && kbConfig.sources != null && !kbConfig.sources.isEmpty()) {
                        new Thread(() -> {
                            try {
                                ConsoleLog.info("[RAG]", "开始加载知识库 [%s] 的文档...", kbId);
                                KnowledgeBaseConfig kbCfg = toKnowledgeBaseConfig(kbConfig);
                                int totalChunks = ragService.initKnowledgeBase(kbCfg);
                                ConsoleLog.ragIndex(kbId, totalChunks);
                            } catch (Exception e) {
                                ConsoleLog.warn("[RAG]", "知识库 [%s] 加载失败: %s", kbId, e.getMessage());
                            }
                        }).start();
                    }
                }
                break;

            case DELETE:
                knowledgeBaseConfigs.remove(kbId);
                ConsoleLog.info("[Dynamic]", "知识库配置 [%s] 已删除", kbId);

                // 删除知识库索引
                if (ragService != null) {
                    try {
                        ragService.deleteKnowledgeBase(kbId);
                        log.info("知识库 [{}] 索引已删除", kbId);
                    } catch (Exception e) {
                        log.warn("知识库 [{}] 删除失败: {}", kbId, e.getMessage());
                    }
                }
                break;
        }
    }

    /**
     * 处理技能配置变更
     */
    private void handleSkillConfigChange(ConfigEvent event) {
        if (skillManager == null) {
            ConsoleLog.warn("[Dynamic]", "技能管理器未设置，忽略技能配置变更: %s", event.getConfigId());
            return;
        }

        String skillId = event.getConfigId();

        switch (event.getType()) {
            case PUT:
            case UPDATE:
                Skill skill = parseSkillConfig(event.getNewValue());
                if (skill != null) {
                    skillManager.registerSkill(skill);
                    ConsoleLog.info("[Dynamic]", "技能 [%s] 已注册（来源: etcd）", skillId);
                }
                break;

            case DELETE:
                skillManager.unregisterSkill(skillId);
                break;
        }
    }
    
    /**
     * 解析 Agent 配置
     */
    private AgentConfig parseAgentConfig(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            return objectMapper.readValue(json, AgentConfig.class);
        } catch (Exception e) {
            log.error("解析 Agent 配置失败: {}", json, e);
            return null;
        }
    }
    
    /**
     * 解析模型配置
     */
    private ModelConfigWrapper parseModelConfig(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        
        try {
            return objectMapper.readValue(json, ModelConfigWrapper.class);
        } catch (Exception e) {
            log.error("解析模型配置失败: {}", json, e);
            return null;
        }
    }
    
    /**
     * 解析知识库配置
     */
    private KnowledgeBaseConfigWrapper parseKnowledgeBaseConfig(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, KnowledgeBaseConfigWrapper.class);
        } catch (Exception e) {
            log.error("解析知识库配置失败: {}", json, e);
            return null;
        }
    }

    /**
     * 解析技能配置（从 etcd JSON）
     */
    private Skill parseSkillConfig(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, Skill.class);
        } catch (Exception e) {
            log.error("解析技能配置失败: {}", json, e);
            return null;
        }
    }
    
    /**
     * 获取 Agent 注册中心
     */
    public AgentRegistry getAgentRegistry() {
        return agentRegistry;
    }

    /**
     * 将 DynamicConfigManager.KnowledgeBaseConfigWrapper 转换为 KnowledgeBaseConfig
     */
    private KnowledgeBaseConfig toKnowledgeBaseConfig(KnowledgeBaseConfigWrapper wrapper) {
        if (wrapper == null) return null;
        KnowledgeBaseConfig cfg = new KnowledgeBaseConfig();
        cfg.setId(wrapper.id);
        cfg.setName(wrapper.name);
        cfg.setDescription(wrapper.description);
        if (wrapper.sources != null) {
            java.util.List<KnowledgeBaseConfig.SourceConfig> sources = new java.util.ArrayList<>();
            for (SourceConfig src : wrapper.sources) {
                KnowledgeBaseConfig.SourceConfig sc = new KnowledgeBaseConfig.SourceConfig();
                sc.setType(src.type);
                sc.setPath(src.path);
                sc.setExtensions(src.extensions);
                sc.setUrls(src.urls);
                sources.add(sc);
            }
            cfg.setSources(sources);
        }
        return cfg;
    }
    
    /**
     * 获取模型配置
     */
    public Map<String, ModelConfigWrapper> getModelConfigs() {
        return modelConfigs;
    }
    
    /**
     * 获取知识库配置
     */
    public Map<String, KnowledgeBaseConfigWrapper> getKnowledgeBaseConfigs() {
        return knowledgeBaseConfigs;
    }
    
    /**
     * 停止动态配置管理器
     */
    public void stop() {
        configWatcher.stop();
        agentRegistry.clear();
        modelConfigs.clear();
        knowledgeBaseConfigs.clear();
        
        ConsoleLog.info("[Dynamic]", "动态配置管理器已停止");
    }
    
    /**
     * 模型配置包装类
     */
    public static class ModelConfigWrapper {
        public String provider;
        public String apiKey;
        public String baseUrl;
        public String modelName;
        public double temperature;
        public int maxTokens;
    }
    
    /**
     * 知识库配置包装类
     */
    public static class KnowledgeBaseConfigWrapper {
        public String id;
        public String name;
        public String description;
        public java.util.List<SourceConfig> sources;
    }
    
    /**
     * 数据源配置
     */
    public static class SourceConfig {
        public String type;
        public String path;
        public java.util.List<String> extensions;
        public java.util.List<String> urls;
    }
}