package com.bitguide.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP Server 信息
 * 描述已连接的 MCP Server 的详细信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerInfo {
    
    /**
     * Server 名称（配置中的名称）
     */
    private String name;
    
    /**
     * Server 协议版本
     */
    private String protocolVersion;
    
    /**
     * Server 实现信息
     */
    private ImplementationInfo implementation;
    
    /**
     * Server 能力
     */
    private McpCapabilities capabilities;
    
    /**
     * Server 实现信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImplementationInfo {
        /**
         * 实现名称
         */
        private String name;
        
        /**
         * 实现版本
         */
        private String version;
    }
}