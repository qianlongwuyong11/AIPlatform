package com.bitguide.mcp.transport;

import com.bitguide.mcp.McpConfig;
import com.bitguide.mcp.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * stdio 传输实现
 * 通过标准输入/输出与 MCP Server 进程通信
 */
@Slf4j
public class StdioMcpTransport implements McpTransport {
    
    private final McpConfig.ServerConfig config;
    private final ObjectMapper objectMapper;
    private final AtomicInteger requestId = new AtomicInteger(1);
    
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private volatile boolean running = false;
    
    // 响应等待队列
    private final Map<Object, CountDownLatch> responseLatches = new ConcurrentHashMap<>();
    private final Map<Object, McpJsonRpcResponse> responses = new ConcurrentHashMap<>();
    
    // Server 信息
    private McpServerInfo serverInfo;
    
    public StdioMcpTransport(McpConfig.ServerConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public McpServerInfo connect() throws Exception {
        log.info("连接 MCP Server (stdio): {}", config.getName());
        
        // 构建命令
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        if (config.getArgs() != null) {
            command.addAll(config.getArgs());
        }
        
        // 构建进程启动器
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        
        // 设置环境变量
        if (config.getEnv() != null) {
            pb.environment().putAll(config.getEnv());
        }
        
        // 启动进程
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        
        // 启动后台线程读取响应
        running = true;
        readerThread = new Thread(this::readResponses, "mcp-stdio-reader-" + config.getName());
        readerThread.setDaemon(true);
        readerThread.start();
        
        // 启动错误流读取线程（用于调试）
        Thread errorThread = new Thread(this::readErrors, "mcp-stdio-error-" + config.getName());
        errorThread.setDaemon(true);
        errorThread.start();
        
        // 发送初始化请求
        McpJsonRpcRequest initRequest = McpJsonRpcRequest.createInitialize("2024-11-05");
        McpJsonRpcResponse initResponse = sendRequest(initRequest);
        
        if (!initResponse.isSuccess()) {
            throw new RuntimeException("MCP 初始化失败: " + initResponse.getErrorMessage());
        }
        
        // 解析 Server 信息
        serverInfo = parseServerInfo(initResponse.getResult(), config.getName());
        
        // 发送 initialized 通知
        sendNotification(McpJsonRpcRequest.createInitialized());
        
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
        if (!running) {
            throw new RuntimeException("MCP Transport 未连接");
        }
        
        // 分配请求 ID
        Object id = request.getId() != null ? request.getId() : requestId.getAndIncrement();
        request.setId(id);
        
        // 准备响应等待
        CountDownLatch latch = new CountDownLatch(1);
        responseLatches.put(id, latch);
        
        try {
            // 发送请求
            String json = objectMapper.writeValueAsString(request);
            log.debug("发送 MCP 请求: {}", json);
            
            writer.write(json);
            writer.newLine();
            writer.flush();
            
            // 等待响应
            int timeout = config.getTimeout() != null ? config.getTimeout() : 30000;
            if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("MCP 请求超时: " + request.getMethod());
            }
            
            return responses.remove(id);
            
        } finally {
            responseLatches.remove(id);
        }
    }
    
    @Override
    public void sendNotification(McpJsonRpcRequest request) throws Exception {
        if (!running) {
            throw new RuntimeException("MCP Transport 未连接");
        }
        
        String json = objectMapper.writeValueAsString(request);
        log.debug("发送 MCP 通知: {}", json);
        
        writer.write(json);
        writer.newLine();
        writer.flush();
    }
    
    /**
     * 后台线程：读取响应
     */
    private void readResponses() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                log.debug("收到 MCP 响应: {}", line);
                
                try {
                    McpJsonRpcResponse response = objectMapper.readValue(line, McpJsonRpcResponse.class);
                    
                    // 如果有 ID，则匹配等待的请求
                    if (response.getId() != null) {
                        responses.put(response.getId(), response);
                        CountDownLatch latch = responseLatches.remove(response.getId());
                        if (latch != null) {
                            latch.countDown();
                        }
                    } else {
                        // 通知消息（如工具列表变更）
                        log.info("收到 MCP 通知: {}", response);
                    }
                } catch (Exception e) {
                    log.warn("解析 MCP 响应失败: {} - {}", line, e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) {
                log.error("读取 MCP 响应失败", e);
            }
        } finally {
            running = false;
        }
    }
    
    /**
     * 后台线程：读取错误流
     */
    private void readErrors() {
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = errorReader.readLine()) != null) {
                log.warn("[MCP Server {}] {}", config.getName(), line);
            }
        } catch (IOException e) {
            log.debug("读取 MCP 错误流结束", e);
        }
    }
    
    @Override
    public void disconnect() {
        log.info("断开 MCP Server 连接: {}", config.getName());
        running = false;
        
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Exception e) {
            log.debug("关闭 writer 失败", e);
        }
        
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception e) {
            log.debug("关闭 reader 失败", e);
        }
        
        try {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception e) {
            log.debug("销毁进程失败", e);
        }
        
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
    
    @Override
    public boolean isConnected() {
        return running && process != null && process.isAlive();
    }

    @Override
    public McpServerInfo reconnect() throws Exception {
        log.info("重连 MCP Server (stdio): {}", config.getName());
        disconnect();
        return connect();
    }

    @Override
    public String getServerName() {
        return config.getName();
    }
}