package com.bitguide.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP Server 能力
 * 描述 Server 支持的功能
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpCapabilities {
    
    /**
     * 工具能力
     */
    private ToolCapabilities tools;
    
    /**
     * 资源能力
     */
    private ResourceCapabilities resources;
    
    /**
     * 提示能力
     */
    private PromptCapabilities prompts;
    
    /**
     * 日志能力
     */
    private LoggingCapabilities logging;
    
    /**
     * 工具能力详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCapabilities {
        /**
         * 是否支持工具列表变更通知
         */
        private Boolean listChanged;
    }
    
    /**
     * 资源能力详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceCapabilities {
        /**
         * 是否支持资源列表变更通知
         */
        private Boolean listChanged;
        
        /**
         * 是否支持资源订阅
         */
        private Boolean subscribe;
    }
    
    /**
     * 提示能力详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptCapabilities {
        /**
         * 是否支持提示列表变更通知
         */
        private Boolean listChanged;
    }
    
    /**
     * 日志能力详情
     */
    @Data
    @Builder
    @NoArgsConstructor
    public static class LoggingCapabilities {
        // 日志相关能力（暂无字段）
    }
}