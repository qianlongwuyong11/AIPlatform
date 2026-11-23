package com.bitguide.gateway.distributed;

import com.bitguide.agent.Agent;
import com.bitguide.agent.AgentEntry;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.config.RedisConfig;
import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.model.GatewayRequest;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import com.bitguide.gateway.security.*;
import com.bitguide.memory.DistributedMemory;
import com.bitguide.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 分布式 Agent 网关
 * 使用 Redis 实现分布式 Lane 和限流
 * 
 * 注意：这是一个新类，不修改原有的 AgentGateway
 */
@Slf4j
public class DistributedAgentGateway {
    
    private final AgentRegistry agentRegistry;
    private final DistributedMemory distributedMemory;
    private final RedisLaneManager laneManager;
    private final RedisRateLimitManager rateLimitManager;
    private final GatewayConfig gatewayConfig;
    private final List<SecurityValidator> securityValidators;

    /**
     * 异步处理线程池
     */
    private final ExecutorService executorService;

    public DistributedAgentGateway(
            AgentRegistry agentRegistry,
            DistributedMemory distributedMemory,
            RedisClient redisClient,
            RedisConfig redisConfig,
            GatewayConfig gatewayConfig) {
        this.agentRegistry = agentRegistry;
        this.distributedMemory = distributedMemory;
        this.laneManager = new RedisLaneManager(redisClient, redisConfig);
        this.rateLimitManager = new RedisRateLimitManager(redisClient, redisConfig, gatewayConfig);
        this.gatewayConfig = gatewayConfig;
        this.executorService = Executors.newFixedThreadPool(
                gatewayConfig.getGlobalMaxConcurrent(),
                r -> {
                    Thread t = new Thread(r, "gateway-worker");
                    t.setDaemon(true);
                    return t;
                }
        );

        // 初始化安全校验器
        this.securityValidators = new ArrayList<>();
        this.securityValidators.add(new InputLengthValidator(gatewayConfig));
        this.securityValidators.add(new PromptInjectionValidator(gatewayConfig));
        this.securityValidators.add(new SensitiveWordValidator(gatewayConfig));

        log.info("分布式 Agent 网关初始化完成（含安全校验）");
    }
    
    /**
     * 处理请求
     * 
     * @param request 网关请求
     * @return 网关响应
     */
    public GatewayResponse process(GatewayRequest request) {
        long startTime = System.currentTimeMillis();
        String sessionId = request.getSessionId();
        
        log.info("收到请求: sessionId={}, agentId={}, userId={}", 
                sessionId, request.getAgentId(), request.getUserId());
        
        try {
            // 1. 构建请求上下文
            RequestContext context = buildContext(request);

            // 2. 认证检查
            if (gatewayConfig.isAuthEnabled()) {
                String token = request.getToken();
                if (token == null || token.isEmpty()) {
                    return GatewayResponse.unauthorized("Token is required");
                }
                String userId = gatewayConfig.getApiKeys().get(token);
                if (userId == null) {
                    log.warn("无效的token: sessionId={}", sessionId);
                    return GatewayResponse.unauthorized("Invalid token");
                }
                context.setAuthenticatedUserId(userId);
            }

            // 3. 安全校验（输入长度、Prompt 注入检测、敏感词过滤）
            if (gatewayConfig.isSecurityEnabled()) {
                String message = request.getMessage();
                for (SecurityValidator validator : securityValidators) {
                    SecurityValidator.ValidationResult result = validator.validate(message);
                    if (!result.passed()) {
                        log.warn("安全校验未通过 [{}]: reason={}, sessionId={}",
                                validator.getName(), result.blockedReason(), sessionId);
                        return GatewayResponse.failure("SECURITY_BLOCKED", result.blockedReason());
                    }
                }
            }

            // 4. 限流检查
            GatewayResponse rateLimitResponse = rateLimitManager.check(context);
            if (rateLimitResponse != null) {
                return rateLimitResponse;
            }
            
            // 5. 尝试进入 Lane（等待最多 30 秒）
            if (!laneManager.tryEnter(sessionId, 30000)) {
                log.warn("Lane 等待超时: sessionId={}", sessionId);
                return GatewayResponse.failure("SYSTEM_BUSY", "系统繁忙，请稍后重试");
            }
            
            try {
                // 6. 获取 Agent（引用计数保护）
                AgentEntry entry = agentRegistry.acquire(request.getAgentId());
                if (entry == null) {
                    return GatewayResponse.failure("AGENT_NOT_FOUND", "Agent 不存在: " + request.getAgentId());
                }

                try {
                    Agent agent = entry.getAgent();

                    // 7. 记录用户消息（分布式内存，按 sessionId）
                    distributedMemory.recordUserMessage(sessionId, request.getMessage());

                    // 8. 调用 Agent 处理
                    // 使用 sessionId:agentId 作为 key，确保每个 Agent 的会话历史独立隔离
                    String agentSessionKey = sessionId + ":" + request.getAgentId();
                    String response = agent.chat(request.getMessage(), agentSessionKey);

                    // 9. 记录助手响应（分布式内存，按 sessionId）
                    distributedMemory.recordAssistantResponse(sessionId, response);

                    // 10. 构建成功响应
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("请求处理完成: sessionId={}, duration={}ms", sessionId, duration);

                    return GatewayResponse.success(response, sessionId, request.getAgentId());

                } finally {
                    // 11. 释放 Agent 引用计数
                    agentRegistry.release(request.getAgentId());
                }

            } finally {
                // 12. 离开 Lane
                laneManager.leave(sessionId);
            }
            
        } catch (Exception e) {
            log.error("请求处理失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return GatewayResponse.failure("INTERNAL_ERROR", "内部错误: " + e.getMessage());
        }
    }
    
    /**
     * 异步处理请求
     * 
     * @param request 网关请求
     * @return 异步响应
     */
    public CompletableFuture<GatewayResponse> processAsync(GatewayRequest request) {
        return CompletableFuture.supplyAsync(() -> process(request), executorService);
    }
    
    /**
     * 构建请求上下文
     */
    private RequestContext buildContext(GatewayRequest request) {
        return RequestContext.builder()
                .request(request)
                .authenticatedUserId(request.getUserId())
                .routedAgentId(request.getAgentId())
                .build();
    }
    
    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return distributedMemory.getActiveSessionCount();
    }
    
    /**
     * 获取活跃 Lane 数
     */
    public int getActiveLaneCount() {
        return laneManager.getActiveLaneCount();
    }
    
    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        distributedMemory.clearSession(sessionId);
        laneManager.forceClear(sessionId);
    }
    
    /**
     * 关闭网关
     */
    public void shutdown() {
        log.info("关闭分布式 Agent 网关...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("分布式 Agent 网关已关闭");
    }
}