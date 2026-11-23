package com.bitguide.mcp;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * MCP 配置类
 * 支持 stdio 和 http 两种传输方式
 */
@Data
public class McpConfig {
    
    /**
     * 是否启用 MCP
     */
    private Boolean enabled = true;
    
    /**
     * MCP 协议版本
     */
    private String protocolVersion = "2024-11-05";
    
    /**
     * MCP Server 列表
     */
    private List<ServerConfig> servers;
    
    /**
     * 单个 MCP Server 配置
     */
    @Data
    public static class ServerConfig {
        /**
         * Server 名称（用于标识和日志）
         */
        private String name;
        
        /**
         * 传输类型: stdio, http
         */
        private String transport;
        
        // ========== stdio 传输配置 ==========
        
        /**
         * 启动命令（stdio 方式）
         * 例如: npx, python, node
         */
        private String command;
        
        /**
         * 命令参数（stdio 方式）
         */
        private List<String> args;
        
        /**
         * 环境变量（stdio 方式）
         */
        private Map<String, String> env;
        
        // ========== http 传输配置 ==========
        
        /**
         * Server URL（http 方式）
         */
        private String url;
        
        /**
         * 请求头（http 方式）
         * 例如: Authorization
         */
        private Map<String, String> headers;
        
        /**
         * 请求超时时间（毫秒）
         */
        private Integer timeout = 30000;
        
        /**
         * 判断是否为 stdio 传输
         */
        public boolean isStdio() {
            return "stdio".equalsIgnoreCase(transport);
        }
        
        /**
         * 判断是否为 http 传输
         */
        public boolean isHttp() {
            return "http".equalsIgnoreCase(transport) || "https".equalsIgnoreCase(transport);
        }
    }
}