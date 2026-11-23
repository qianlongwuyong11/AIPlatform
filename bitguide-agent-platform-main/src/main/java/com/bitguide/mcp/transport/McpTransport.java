package com.bitguide.mcp.transport;

import com.bitguide.mcp.model.McpJsonRpcRequest;
import com.bitguide.mcp.model.McpJsonRpcResponse;
import com.bitguide.mcp.model.McpServerInfo;

import java.util.List;

/**
 * MCP 传输层接口
 * 定义 MCP 客户端与 Server 之间的通信方式
 */
public interface McpTransport {
    
    /**
     * 连接到 MCP Server
     * @return Server 信息
     * @throws Exception 连接失败
     */
    McpServerInfo connect() throws Exception;
    
    /**
     * 发送请求并等待响应
     * @param request JSON-RPC 请求
     * @return JSON-RPC 响应
     * @throws Exception 发送失败
     */
    McpJsonRpcResponse sendRequest(McpJsonRpcRequest request) throws Exception;
    
    /**
     * 发送通知（无需响应）
     * @param request JSON-RPC 通知
     * @throws Exception 发送失败
     */
    void sendNotification(McpJsonRpcRequest request) throws Exception;
    
    /**
     * 断开连接
     */
    void disconnect();
    
    /**
     * 是否已连接
     */
    boolean isConnected();

    /**
     * 重新连接（断开后重建连接）
     * @return Server 信息
     * @throws Exception 重连失败
     */
    McpServerInfo reconnect() throws Exception;

    /**
     * 获取 Server 名称
     */
    String getServerName();
}