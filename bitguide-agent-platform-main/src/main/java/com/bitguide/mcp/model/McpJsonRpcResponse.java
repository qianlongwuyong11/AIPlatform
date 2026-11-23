package com.bitguide.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * MCP JSON-RPC 响应
 * MCP 协议基于 JSON-RPC 2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpJsonRpcResponse {
    
    /**
     * JSON-RPC 版本，固定为 "2.0"
     */
    private String jsonrpc;
    
    /**
     * 请求 ID（与请求匹配）
     */
    private Object id;
    
    /**
     * 响应结果（成功时）
     */
    private Map<String, Object> result;
    
    /**
     * 错误信息（失败时）
     */
    private McpError error;
    
    /**
     * MCP 错误
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpError {
        /**
         * 错误码
         */
        private Integer code;
        
        /**
         * 错误消息
         */
        private String message;
        
        /**
         * 错误数据
         */
        private Object data;
    }
    
    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return error == null;
    }
    
    /**
     * 获取错误描述
     */
    public String getErrorMessage() {
        if (error == null) {
            return null;
        }
        return error.getMessage() + (error.getData() != null ? " - " + error.getData() : "");
    }
}