package com.bitguide.config.dynamic;

import lombok.Getter;

/**
 * 配置变更事件
 */
@Getter
public class ConfigEvent {
    
    /**
     * 事件类型
     */
    private final ConfigEventType type;
    
    /**
     * 配置类型（agents, models, prompts, knowledgebases）
     */
    private final String configType;
    
    /**
     * 配置 ID
     */
    private final String configId;
    
    /**
     * 完整的 Etcd Key
     */
    private final String key;
    
    /**
     * 新值（JSON 字符串）
     */
    private final String newValue;
    
    /**
     * 旧值（JSON 字符串，仅 UPDATE 时有值）
     */
    private final String oldValue;
    
    /**
     * 事件时间戳
     */
    private final long timestamp;
    
    public ConfigEvent(ConfigEventType type, String key, String newValue, String oldValue) {
        this.type = type;
        this.key = key;
        this.newValue = newValue;
        this.oldValue = oldValue;
        this.timestamp = System.currentTimeMillis();
        
        // 解析 key 获取 configType 和 configId
        // key 格式: /bitguide/config/agents/parenting_advisor
        String[] parts = key.split("/");
        if (parts.length >= 4) {
            this.configType = parts[3]; // agents, models, prompts, knowledgebases
            this.configId = parts.length >= 5 ? parts[4] : "";
        } else {
            this.configType = "";
            this.configId = "";
        }
    }
    
    /**
     * 是否是 Agent 配置变更
     */
    public boolean isAgentConfig() {
        return "agents".equals(configType);
    }
    
    /**
     * 是否是模型配置变更
     */
    public boolean isModelConfig() {
        return "models".equals(configType);
    }
    
    /**
     * 是否是提示词配置变更
     */
    public boolean isPromptConfig() {
        return "prompts".equals(configType);
    }
    
    /**
     * 是否是知识库配置变更
     */
    public boolean isKnowledgeBaseConfig() {
        return "knowledgebases".equals(configType);
    }

    /**
     * 是否是技能配置变更
     */
    public boolean isSkillConfig() {
        return "skills".equals(configType);
    }
    
    @Override
    public String toString() {
        return "ConfigEvent{" +
                "type=" + type +
                ", configType='" + configType + '\'' +
                ", configId='" + configId + '\'' +
                ", key='" + key + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}