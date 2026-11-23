package com.bitguide.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP JSON-RPC 请求
 * MCP 协议基于 JSON-RPC 2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpJsonRpcRequest {
    
    /**
     * JSON-RPC 版本，固定为 "2.0"
     */
    @Builder.Default
    private String jsonrpc = "2.0";
    
    /**
     * 请求 ID（用于匹配响应）
     */
    private Object id;
    
    /**
     * 方法名
     */
    private String method;
    
    /**
     * 参数
     */
    private Map<String, Object> params;
    
    /**
     * 创建初始化请求
     */
    public static McpJsonRpcRequest createInitialize(String protocolVersion) {
        return McpJsonRpcRequest.builder()
                .id(1)
                .method("initialize")
                .params(Map.of(
                    "protocolVersion", protocolVersion,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of(
                        "name", "bitguide-mcp-client",
                        "version", "1.0.0"
                    )
                ))
                .build();
    }
    
    /**
     * 创建 initialized 通知
     */
    public static McpJsonRpcRequest createInitialized() {
        return McpJsonRpcRequest.builder()
                .method("notifications/initialized")
                .build();
    }
    
    /**
     * 创建列出工具请求
     */
    public static McpJsonRpcRequest createListTools(Object id) {
        return McpJsonRpcRequest.builder()
                .id(id)
                .method("tools/list")
                .build();
    }
    
    /**
     * 创建调用工具请求
     */
    public static McpJsonRpcRequest createCallTool(Object id, String toolName, Map<String, Object> arguments) {
        return McpJsonRpcRequest.builder()
                .id(id)
                .method("tools/call")
                .params(Map.of(
                    "name", toolName,
                    "arguments", arguments != null ? arguments : Map.of()
                ))
                .build();
    }
    
    /**
     * 创建列出资源请求
     */
    public static McpJsonRpcRequest createListResources(Object id) {
        return McpJsonRpcRequest.builder()
                .id(id)
                .method("resources/list")
                .build();
    }
    
    /**
     * 创建列出提示请求
     */
    public static McpJsonRpcRequest createListPrompts(Object id) {
        return McpJsonRpcRequest.builder()
                .id(id)
                .method("prompts/list")
                .build();
    }
}