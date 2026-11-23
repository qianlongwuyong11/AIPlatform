package com.bitguide.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP 工具定义
 * 描述 MCP Server 提供的工具信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDefinition {
    
    /**
     * 工具名称
     */
    private String name;
    
    /**
     * 工具描述
     */
    private String description;
    
    /**
     * 输入参数 Schema (JSON Schema 格式)
     */
    private Map<String, Object> inputSchema;
    
    /**
     * 所属 Server 名称
     */
    private String serverName;
    
    /**
     * 获取参数 Schema 的 JSON 字符串
     */
    public String getInputSchemaJson() {
        if (inputSchema == null) {
            return "{}";
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writeValueAsString(inputSchema);
        } catch (Exception e) {
            return "{}";
        }
    }
}