package com.bitguide.mcp.transport;

import com.bitguide.mcp.McpConfig;
import com.bitguide.mcp.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP 传输实现
 * 通过 HTTP 与远程 MCP Server 通信 (Streamable HTTP)
 */
@Slf4j
public class HttpMcpTransport implements McpTransport {
    
    private final McpConfig.ServerConfig config;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final AtomicInteger requestId = new AtomicInteger(1);
    
    private McpServerInfo serverInfo;
    private volatile boolean connected = false;
    
    public HttpMcpTransport(McpConfig.ServerConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        
        // 构建 HTTP 客户端
        int timeout = config.getTimeout() != null ? config.getTimeout() : 30000;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }
    
    @Override
    public McpServerInfo connect() throws Exception {
        log.info("连接 MCP Server (http): {} - {}", config.getName(), config.getUrl());
        
        // 发送初始化请求
        McpJsonRpcRequest initRequest = McpJsonRpcRequest.createInitialize("2024-11-05");
        McpJsonRpcResponse initResponse = sendRequest(initRequest);
        
        if (!initResponse.isSuccess()) {
            throw new RuntimeException("MCP 初始化失败: " + initResponse.getErrorMessage());
        }
        
        // 解析 Server 信息
        serverInfo = parseServerInfo(initResponse.getResult(), config.getName());
        connected = true;
        
        log.info("MCP Server 连接成功: {} ({})", serverInfo.getName(), serverInfo.getProtocolVersion());
        
        return serverInfo;
    }
    
    /**
     * 解析 Server 信息
     */
    @SuppressWarnings("unchecked")
    private McpServerInfo parseServerInfo(Map<String, Object> result, String name) {
        McpServerInfo info = new McpServerInfo();
        info.setName(name);
        
        if (result != null) {
            info.setProtocolVersion((String) result.get("protocolVersion"));
            
            Object serverInfoObj = result.get("serverInfo");
            if (serverInfoObj instanceof Map) {
                Map<String, Object> si = (Map<String, Object>) serverInfoObj;
                info.setImplementation(McpServerInfo.ImplementationInfo.builder()
                        .name((String) si.get("name"))
                        .version((String) si.get("version"))
                        .build());
            }
            
            Object capabilitiesObj = result.get("capabilities");
            if (capabilitiesObj instanceof Map) {
                info.setCapabilities(parseCapabilities((Map<String, Object>) capabilitiesObj));
            }
        }
        
        return info;
    }
    
    /**
     * 解析能力
     */
    @SuppressWarnings("unchecked")
    private McpCapabilities parseCapabilities(Map<String, Object> caps) {
        McpCapabilities.McpCapabilitiesBuilder builder = McpCapabilities.builder();
        
        if (caps.get("tools") instanceof Map) {
            Map<String, Object> tools = (Map<String, Object>) caps.get("tools");
            builder.tools(McpCapabilities.ToolCapabilities.builder()
                    .listChanged((Boolean) tools.get("listChanged"))
                    .build());
        }
        
        if (caps.get("resources") instanceof Map) {
            Map<String, Object> resources = (Map<String, Object>) caps.get("resources");
            builder.resources(McpCapabilities.ResourceCapabilities.builder()
                    .listChanged((Boolean) resources.get("listChanged"))
                    .subscribe((Boolean) resources.get("subscribe"))
                    .build());
        }
        
        if (caps.get("prompts") instanceof Map) {
            Map<String, Object> prompts = (Map<String, Object>) caps.get("prompts");
            builder.prompts(McpCapabilities.PromptCapabilities.builder()
                    .listChanged((Boolean) prompts.get("listChanged"))
                    .build());
        }
        
        return builder.build();
    }
    
    @Override
    public McpJsonRpcResponse sendRequest(McpJsonRpcRequest request) throws Exception {
        // 分配请求 ID
        if (request.getId() == null) {
            request.setId(requestId.getAndIncrement());
        }
        
        // 构建请求体
        String json = objectMapper.writeValueAsString(request);
        log.debug("发送 MCP HTTP 请求: {}", json);
        
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        
        // 构建请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(config.getUrl())
                .post(body);
        
        // 添加请求头
        requestBuilder.addHeader("Content-Type", "application/json");
        if (config.getHeaders() != null) {
            for (Map.Entry<String, String> header : config.getHeaders().entrySet()) {
                requestBuilder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        // 发送请求
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("MCP HTTP 请求失败: " + response.code() + " " + response.message());
            }
            
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("MCP HTTP 响应体为空");
            }
            
            String responseJson = responseBody.string();
            log.debug("收到 MCP HTTP 响应: {}", responseJson);
            
            return objectMapper.readValue(responseJson, McpJsonRpcResponse.class);
        }
    }
    
    @Override
    public void sendNotification(McpJsonRpcRequest request) throws Exception {
        // HTTP 模式下，通知也通过 POST 发送，但不等待响应
        String json = objectMapper.writeValueAsString(request);
        log.debug("发送 MCP HTTP 通知: {}", json);
        
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(config.getUrl())
                .post(body);
        
        requestBuilder.addHeader("Content-Type", "application/json");
        if (config.getHeaders() != null) {
            for (Map.Entry<String, String> header : config.getHeaders().entrySet()) {
                requestBuilder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                log.warn("MCP HTTP 通知发送失败: {} {}", response.code(), response.message());
            }
        }
    }
    
    @Override
    public void disconnect() {
        log.info("断开 MCP Server 连接: {}", config.getName());
        connected = false;
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public String getServerName() {
        return config.getName();
    }

    @Override
    public McpServerInfo reconnect() throws Exception {
        log.info("重连 MCP Server (http): {}", config.getName());
        disconnect();
        return connect();
    }
}