package com.bitguide.config;

import com.bitguide.mcp.McpConfig;
import lombok.Data;

import java.util.List;

/**
 * Agent配置
 * 支持动态装配：模型、提示词、工具、知识库、记忆、技能
 */
@Data
public class AgentConfig {
    private String id;
    private String name;
    private String description;
    private String modelRef;

    /** 系统提示词 */
    private String systemPrompt;

    /** 工具引用列表 */
    private List<String> toolRefs;

    /** 技能引用列表 */
    private List<String> skillRefs;

    /** 知识库引用（用于 RAG） */
    private String knowledgeBase;

    /** MCP Server 引用列表（向后兼容，引用全局 yml 预定义的 server） */
    private List<String> mcpServerRefs;

    /**
     * MCP Server 内联定义（推荐方式）
     * Agent 配置中直接包含 MCP Server 连接信息，推送配置即生效
     * 优先级高于 mcpServerRefs
     */
    private List<McpConfig.ServerConfig> mcpServers;

    /** 记忆配置 */
    private MemoryConfig memory;

    @Data
    public static class MemoryConfig {
        private boolean enabled = true;
        private int maxHistoryLength = 50;
        private long sessionTimeoutMs = 3600000;
        private int compressionThreshold = 30;
    }

    public MemoryConfig getMemory() {
        if (memory == null) {
            memory = new MemoryConfig();
        }
        return memory;
    }
}
