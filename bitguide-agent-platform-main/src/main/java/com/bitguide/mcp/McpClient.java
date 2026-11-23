package com.bitguide.mcp;

import com.bitguide.log.ConsoleLog;
import com.bitguide.mcp.model.McpJsonRpcRequest;
import com.bitguide.mcp.model.McpJsonRpcResponse;
import com.bitguide.mcp.model.McpServerInfo;
import com.bitguide.mcp.model.McpToolDefinition;
import com.bitguide.mcp.transport.HttpMcpTransport;
import com.bitguide.mcp.transport.McpTransport;
import com.bitguide.mcp.transport.StdioMcpTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端
 * 管理多个 MCP Server 连接，发现和调用工具
 */
@Slf4j
public class McpClient {
    
    private final McpConfig config;
    private final Map<String, McpTransport> transports = new ConcurrentHashMap<>();
    private final Map<String, McpServerInfo> serverInfos = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requestIds = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public McpClient(McpConfig config) {
        this.config = config;
    }
    
    /**
     * 连接所有配置的 MCP Server
     */
    public void connectAll() {
        if (config.getServers() == null || config.getServers().isEmpty()) {
            log.info("没有配置 MCP Server");
            return;
        }
        
        for (McpConfig.ServerConfig serverConfig : config.getServers()) {
            try {
                connect(serverConfig);
            } catch (Exception e) {
                ConsoleLog.mcpConnectError(serverConfig.getName(), e);
            }
        }
    }
    
    /**
     * 连接单个 MCP Server
     */
    public McpServerInfo connect(McpConfig.ServerConfig serverConfig) throws Exception {
        String name = serverConfig.getName();
        ConsoleLog.mcpConnect(name + " (" + serverConfig.getTransport() + ")");
        
        // 创建传输层
        McpTransport transport = createTransport(serverConfig);
        
        // 连接
        McpServerInfo info = transport.connect();
        
        // 保存
        transports.put(name, transport);
        serverInfos.put(name, info);
        requestIds.put(name, new AtomicInteger(1));
        
        ConsoleLog.mcpConnectSuccess(name);
        
        return info;
    }
    
    /**
     * 创建传输层
     */
    private McpTransport createTransport(McpConfig.ServerConfig serverConfig) {
        if (serverConfig.isStdio()) {
            return new StdioMcpTransport(serverConfig);
        } else if (serverConfig.isHttp()) {
            return new HttpMcpTransport(serverConfig);
        } else {
            throw new IllegalArgumentException("不支持的传输类型: " + serverConfig.getTransport());
        }
    }
    
    /**
     * 获取所有已连接 Server 的所有工具
     */
    public List<McpTool> getAllTools() {
        List<McpTool> allTools = new ArrayList<>();
        
        for (String serverName : transports.keySet()) {
            try {
                List<McpTool> tools = getTools(serverName);
                allTools.addAll(tools);
            } catch (Exception e) {
                log.error("获取 Server 工具失败: {} - {}", serverName, e.getMessage());
            }
        }
        
        return allTools;
    }
    
    /**
     * 获取指定 Server 的工具列表
     */
    public List<McpTool> getTools(String serverName) throws Exception {
        McpTransport transport = transports.get(serverName);
        if (transport == null) {
            throw new RuntimeException("MCP Server 未连接: " + serverName);
        }
        
        AtomicInteger requestId = requestIds.get(serverName);
        
        // 发送 tools/list 请求
        McpJsonRpcRequest request = McpJsonRpcRequest.createListTools(requestId.getAndIncrement());
        McpJsonRpcResponse response = transport.sendRequest(request);
        
        if (!response.isSuccess()) {
            throw new RuntimeException("获取工具列表失败: " + response.getErrorMessage());
        }
        
        // 解析工具列表
        List<McpTool> tools = new ArrayList<>();
        Object toolsObj = response.getResult().get("tools");
        
        if (toolsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolsList = (List<Map<String, Object>>) toolsObj;
            
            for (Map<String, Object> toolData : toolsList) {
                McpToolDefinition definition = McpToolDefinition.builder()
                        .name((String) toolData.get("name"))
                        .description((String) toolData.get("description"))
                        .inputSchema((Map<String, Object>) toolData.get("inputSchema"))
                        .serverName(serverName)
                        .build();
                
                McpTool tool = new McpTool(definition, transport, requestId);
                tools.add(tool);
                
                log.debug("发现 MCP 工具: {} - {}", tool.getName(), tool.getDescription());
            }
        }
        
        ConsoleLog.mcpToolsList(serverName, tools.size());
        
        return tools;
    }
    
    /**
     * 调用工具
     */
    public String callTool(String serverName, String toolName, Map<String, Object> arguments) throws Exception {
        McpTransport transport = transports.get(serverName);
        if (transport == null) {
            throw new RuntimeException("MCP Server 未连接: " + serverName);
        }
        
        AtomicInteger requestId = requestIds.get(serverName);
        
        McpJsonRpcRequest request = McpJsonRpcRequest.createCallTool(
                requestId.getAndIncrement(),
                toolName,
                arguments
        );
        
        McpJsonRpcResponse response = transport.sendRequest(request);
        
        if (!response.isSuccess()) {
            throw new RuntimeException("工具调用失败: " + response.getErrorMessage());
        }
        
        // 提取结果
        return extractToolOutput(response.getResult());
    }
    
    /**
     * 从响应结果中提取工具输出
     */
    @SuppressWarnings("unchecked")
    private String extractToolOutput(Map<String, Object> result) {
        if (result == null) {
            return "";
        }
        
        Object content = result.get("content");
        if (content instanceof List) {
            List<?> contentList = (List<?>) content;
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
     * 断开所有连接
     */
    public void disconnectAll() {
        for (McpTransport transport : transports.values()) {
            try {
                transport.disconnect();
            } catch (Exception e) {
                log.error("断开 MCP Server 连接失败: {}", transport.getServerName(), e);
            }
        }
        transports.clear();
        serverInfos.clear();
        requestIds.clear();
    }
    
    /**
     * 断开指定 Server 连接
     */
    public void disconnect(String serverName) {
        McpTransport transport = transports.remove(serverName);
        if (transport != null) {
            transport.disconnect();
        }
        serverInfos.remove(serverName);
        requestIds.remove(serverName);
    }
    
    /**
     * 获取已连接的 Server 列表
     */
    public Set<String> getConnectedServers() {
        return Collections.unmodifiableSet(transports.keySet());
    }
    
    /**
     * 获取 Server 信息
     */
    public McpServerInfo getServerInfo(String serverName) {
        return serverInfos.get(serverName);
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected(String serverName) {
        McpTransport transport = transports.get(serverName);
        return transport != null && transport.isConnected();
    }
    
    /**
     * 获取配置
     */
    public McpConfig getConfig() {
        return config;
    }
}