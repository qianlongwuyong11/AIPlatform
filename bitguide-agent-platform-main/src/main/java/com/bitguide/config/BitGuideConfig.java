package com.bitguide.config;

import com.bitguide.config.dynamic.EtcdConfig;
import com.bitguide.mcp.McpConfig;
import lombok.Data;

import java.util.Map;

/**
 * BitGuide 总配置
 */
@Data
public class BitGuideConfig {
    private Map<String, AgentConfig> agents;
    private Map<String, ModelConfig> models;
    private RagConfig rag;
    private Map<String, KnowledgeBaseConfig> knowledgeBases;
    private EtcdConfig etcd;
    
    /**
     * MCP 全局配置
     */
    private McpConfig mcp;
    
    /**
     * Gateway配置（Map形式，便于灵活解析）
     */
    private Map<String, Object> gateway;
    
    /**
     * HTTP Server 配置
     */
    private Map<String, Object> server;
    
    /**
     * Redis 分布式配置（Map形式，便于灵活解析）
     */
    private Map<String, Object> redis;
    
    /**
     * Skills 配置（声明式技能，动态加载）
     */
    private SkillsConfig skills;
    
    /**
     * Skills 全局配置
     */
    @Data
    public static class SkillsConfig {
        /**
         * 是否启用 Skills
         */
        private boolean enabled = false;
        
        /**
         * Skills 目录路径
         */
        private String directory = "skills";
        
        /**
         * 是否启用热重载
         */
        private boolean hotReload = false;
    }
}
