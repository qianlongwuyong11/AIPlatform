package com.bitguide.mcp;

import com.bitguide.mcp.model.McpJsonRpcRequest;
import com.bitguide.mcp.model.McpJsonRpcResponse;
import com.bitguide.mcp.model.McpToolDefinition;
import com.bitguide.mcp.transport.McpTransport;
import com.bitguide.tool.Tool;
import com.bitguide.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 工具包装器
 * 将 MCP 工具包装为统一的 Tool 接口
 */
@Slf4j
public class McpTool implements Tool {
    
    private final McpToolDefinition definition;
    private final McpTransport transport;
    private final AtomicInteger requestId;
    private final ObjectMapper objectMapper;
    
    public McpTool(McpToolDefinition definition, McpTransport transport, AtomicInteger requestId) {
        this.definition = definition;
        this.transport = transport;
        this.requestId = requestId;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public String getName() {
        // 使用 serverName_toolName 格式，避免不同 Server 的工具名称冲突
        return definition.getServerName() + "_" + definition.getName();
    }
    
    /**
     * 获取原始工具名称（不含 server 前缀）
     */
    public String getOriginalName() {
        return definition.getName();
    }
    
    @Override
    public String getDescription() {
        return definition.getDescription();
    }
    
    @Override
    public String getParametersSchema() {
        return definition.getInputSchemaJson();
    }
    
    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            log.info("执行 MCP 工具: {} 参数: {}", getName(), params);

            // 健康检查：如果连接断开，尝试重连
            if (!transport.isConnected()) {
                log.warn("MCP Server [{}] 连接已断开，尝试重连...", transport.getServerName());
                try {
                    transport.reconnect();
                    log.info("MCP Server [{}] 重连成功", transport.getServerName());
                } catch (Exception reconnectErr) {
                    log.error("MCP Server [{}] 重连失败: {}", transport.getServerName(), reconnectErr.getMessage());
                    return ToolResult.failure("MCP Server 连接已断开且重连失败: " + reconnectErr.getMessage());
                }
            }

            // 构建调用请求
            McpJsonRpcRequest request = McpJsonRpcRequest.createCallTool(
                    requestId.getAndIncrement(),
                    definition.getName(),
                    params
            );
            
            // 发送请求
            McpJsonRpcResponse response = transport.sendRequest(request);
            
            if (!response.isSuccess()) {
                log.error("MCP 工具调用失败: {}", response.getErrorMessage());
                return ToolResult.failure("MCP 工具调用失败: " + response.getErrorMessage());
            }
            
            // 解析结果
            String output = extractToolOutput(response.getResult());
            log.info("MCP 工具执行成功: {} -> {}", getName(), output);
            
            return ToolResult.success(output);
            
        } catch (Exception e) {
            log.error("MCP 工具执行异常: {} - {}", getName(), e.getMessage(), e);
            return ToolResult.failure("MCP 工具执行异常: " + e.getMessage());
        }
    }
    
    /**
     * 从响应结果中提取工具输出
     */
    @SuppressWarnings("unchecked")
    private String extractToolOutput(Map<String, Object> result) {
        if (result == null) {
            return "";
        }
        
        // MCP 工具调用结果格式: { content: [{type: "text", text: "..."}], isError: false }
        Object content = result.get("content");
        if (content instanceof java.util.List) {
            java.util.List<?> contentList = (java.util.List<?>) content;
            StringBuilder sb = new StringBuilder();
            for (Object item : contentList) {
                if (item instanceof Map) {
                    Map<String, Object> contentItem = (Map<String, Object>) item;
                    if ("text".equals(contentItem.get("type"))) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(contentItem.get("text"));
                    }
                }
            }
            return sb.toString();
        }
        
        // 兼容其他格式
        if (result.get("output") != null) {
            return String.valueOf(result.get("output"));
        }
        
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return result.toString();
        }
    }
    
    /**
     * 获取工具定义
     */
    public McpToolDefinition getDefinition() {
        return definition;
    }
    
    /**
     * 获取所属 Server 名称
     */
    public String getServerName() {
        return definition.getServerName();
    }
}