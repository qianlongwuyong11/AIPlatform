package com.bitguide.stage1.config;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Stage 1 简化配置类
 * 
 * 只包含亲子教育顾问 Agent 所需的配置字段，
 * 适合初学者理解和学习。
 */
@Data
public class Stage1Config {
    
    /**
     * Agent 配置
     */
    private AgentConfig agent;
    
    /**
     * 模型配置
     */
    private ModelConfig model;
    
    /**
     * 记忆配置
     */
    private MemoryConfig memory;
    
    /**
     * RAG 配置（可选）
     */
    private RagConfig rag;
    
    /**
     * MCP 配置（可选）
     */
    private McpConfig mcp;
    
    /**
     * Skills 配置（可选）
     */
    private SkillsConfig skills;
    
    /**
     * Agent 配置
     */
    @Data
    public static class AgentConfig {
        /**
         * Agent ID
         */
        private String id;
        
        /**
         * Agent 名称
         */
        private String name;
        
        /**
         * 系统提示词
         */
        private String systemPrompt;
        
        /**
         * 工具列表
         */
        private List<String> tools;
        
        /**
         * 知识库 ID（用于 RAG）
         */
        private String knowledgeBase;
    }
    
    /**
     * 模型配置
     */
    @Data
    public static class ModelConfig {
        /**
         * 模型提供商 (ollama, openai, zhipu 等)
         */
        private String provider;
        
        /**
         * API Key
         */
        private String apiKey;
        
        /**
         * API 基础 URL
         */
        private String baseUrl;
        
        /**
         * 模型名称
         */
        private String modelName;
        
        /**
         * 温度参数 (0-1)
         */
        private Double temperature;
        
        /**
         * 最大 token 数
         */
        private Integer maxTokens;
    }
    
    /**
     * 记忆配置
     */
    @Data
    public static class MemoryConfig {
        /**
         * 最大历史记录长度
         */
        private Integer maxHistoryLength = 50;
    }
    
    /**
     * RAG 配置
     */
    @Data
    public static class RagConfig {
        /**
         * 是否启用 RAG
         */
        private Boolean enabled = true;
        
        /**
         * 向量数据库配置
         */
        private VectorStoreConfig vectorStore;
        
        /**
         * Embedding 配置
         */
        private EmbeddingConfig embedding;
        
        /**
         * 文档分割配置
         */
        private SplitterConfig splitter;
        
        /**
         * 检索配置
         */
        private RetrievalConfig retrieval;
    }
    
    /**
     * 向量数据库配置
     */
    @Data
    public static class VectorStoreConfig {
        private String type = "milvus";
        private String host = "localhost";
        private int port = 19530;
        private String collectionPrefix = "bitguide_";
    }
    
    /**
     * Embedding 配置
     */
    @Data
    public static class EmbeddingConfig {
        private String provider = "ollama";
        private String baseUrl = "http://localhost:11434";
        private String modelName = "nomic-embed-text";
        private int dimension = 768;
    }
    
    /**
     * 文档分割配置
     */
    @Data
    public static class SplitterConfig {
        private int maxSegmentSize = 500;
        private int overlap = 50;
    }

    /**
     * 检索配置
     */
    @Data
    public static class RetrievalConfig {
        private int topK = 5;
        private double scoreThreshold = 0.7;
    }
    
    /**
     * MCP 配置
     */
    @Data
    public static class McpConfig {
        /**
         * 是否启用 MCP
         */
        private Boolean enabled = true;
        
        /**
         * MCP Server 列表
         */
        private List<ServerConfig> servers;
    }
    
    /**
     * MCP Server 配置
     */
    @Data
    public static class ServerConfig {
        /**
         * Server 名称
         */
        private String name;
        
        /**
         * 传输类型: stdio, http
         */
        private String transport;
        
        // stdio 配置
        private String command;
        private List<String> args;
        private Map<String, String> env;
        
        // http 配置
        private String url;
        private Map<String, String> headers;
        private Integer timeout = 30000;
    }
    
    /**
     * Skills 配置
     */
    @Data
    public static class SkillsConfig {
        /**
         * 是否启用 Skills
         */
        private Boolean enabled = true;
        
        /**
         * Skills 目录路径
         */
        private String directory = "skills";
        
        /**
         * 是否启用热重载
         */
        private Boolean hotReload = true;
        
        /**
         * 启用的技能列表（为空则加载全部）
         */
        private List<String> enabledSkills;
    }
}
