package com.bitguide.gateway;

import com.bitguide.agent.Agent;
import com.bitguide.agent.AgentEntry;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.gateway.auth.AuthFilter;
import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.filter.*;
import com.bitguide.gateway.model.GatewayRequest;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import com.bitguide.gateway.ratelimit.RateLimitManager;
import com.bitguide.gateway.router.DomainRouter;
import com.bitguide.gateway.router.RoutingResult;
import com.bitguide.gateway.security.*;
import com.bitguide.gateway.session.Lane;
import com.bitguide.gateway.session.MessageStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Agent Gateway 主入口
 * 负责请求的路由、认证、安全校验、限流和会话管理
 */
@Slf4j
public class AgentGateway {
    
    private final GatewayConfig config;
    private final AgentRegistry agentRegistry;
    private final DomainRouter domainRouter;
    private final RateLimitManager rateLimitManager;
    private final FilterChain filterChain;
    
    /**
     * Session车道映射（用于串行处理）
     */
    private final Map<String, Lane> sessionLanes = new ConcurrentHashMap<>();
    
    /**
     * 异步执行器
     */
    private final ExecutorService executorService;
    
    public AgentGateway(GatewayConfig config, AgentRegistry agentRegistry) {
        this.config = config;
        this.agentRegistry = agentRegistry;
        this.domainRouter = new DomainRouter(agentRegistry);
        this.rateLimitManager = new RateLimitManager(config);
        
        // 初始化过滤器链
        this.filterChain = buildFilterChain();
        
        // 初始化线程池
        this.executorService = Executors.newFixedThreadPool(
            config.getGlobalMaxConcurrent(),
            r -> {
                Thread t = new Thread(r, "gateway-worker");
                t.setDaemon(true);
                return t;
            }
        );
        
        log.info("AgentGateway 初始化完成: maxConcurrent={}, authEnabled={}, rateLimitEnabled={}",
                config.getGlobalMaxConcurrent(), config.isAuthEnabled(), config.isRateLimitEnabled());
    }
    
    /**
     * 构建过滤器链
     */
    private FilterChain buildFilterChain() {
        List<GatewayFilter> filters = new ArrayList<>();
        
        // 1. 认证过滤器
        filters.add(new AuthFilter(config));
        
        // 2. 限流过滤器
        filters.add(new RateLimitFilter(rateLimitManager, config));
        
        // 3. 安全过滤器
        List<SecurityValidator> validators = new ArrayList<>();
        validators.add(new InputLengthValidator(config));
        validators.add(new PromptInjectionValidator(config));
        validators.add(new SensitiveWordValidator(config));
        filters.add(new SecurityFilter(config, validators));
        
        return new DefaultFilterChain(filters);
    }
    
    /**
     * 处理请求（同步）
     */
    public GatewayResponse process(GatewayRequest request) {
        return process(request, MessageStrategy.SEQUENTIAL);
    }
    
    /**
     * 处理请求（指定策略）
     */
    public GatewayResponse process(GatewayRequest request, MessageStrategy strategy) {
        long startTime = System.currentTimeMillis();
        
        // 1. 创建请求上下文
        RequestContext context = RequestContext.builder()
                .request(request)
                .build();
        
        try {
            // 2. 执行过滤器链
            GatewayResponse filterResult = filterChain.doFilter(context);
            if (filterResult != null) {
                return filterResult;
            }
            
            // 3. 路由到目标Agent
            RoutingResult routingResult = domainRouter.route(request);
            if (!routingResult.isSuccess()) {
                return GatewayResponse.notFound(routingResult.getErrorMessage());
            }
            
            context.setRoutedAgentId(routingResult.getAgentId());
            
            // 4. 根据消息策略处理
            if (strategy == null) {
                strategy = MessageStrategy.SEQUENTIAL;
            }
            GatewayResponse response = processByStrategy(context, routingResult.getAgentId(), strategy);
            
            // 5. 记录处理时间
            long duration = System.currentTimeMillis() - startTime;
            response.setProcessingTimeMs(duration);
            
            log.info("请求处理完成: sessionId={}, agentId={}, duration={}ms",
                    context.getSessionId(), routingResult.getAgentId(), duration);
            
            return response;
            
        } catch (Exception e) {
            log.error("请求处理异常: sessionId={}", context.getSessionId(), e);
            return GatewayResponse.error("Internal error: " + e.getMessage());
        }
    }
    
    /**
     * 根据策略处理请求
     */
    private GatewayResponse processByStrategy(RequestContext context, String agentId, MessageStrategy strategy) {
        switch (strategy) {
            case SEQUENTIAL:
                return processSequential(context, agentId);
            case PARALLEL:
                return processParallel(context, agentId);
            case CANCEL:
                return processWithCancel(context, agentId);
            case MERGE:
                return processWithMerge(context, agentId);
            default:
                return processSequential(context, agentId);
        }
    }
    
    /**
     * 串行处理 - 同一Session的消息排队处理
     */
    private GatewayResponse processSequential(RequestContext context, String agentId) {
        String sessionId = context.getSessionId();
        Lane lane = sessionLanes.computeIfAbsent(sessionId, Lane::new);
        
        // 尝试进入车道
        while (!lane.tryEnter()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return GatewayResponse.error("Request interrupted");
            }
        }
        
        try {
            return executeAgent(context, agentId);
        } finally {
            lane.leave();
        }
    }
    
    /**
     * 并行处理
     */
    private GatewayResponse processParallel(RequestContext context, String agentId) {
        return executeAgent(context, agentId);
    }
    
    /**
     * 取消策略 - 取消之前的请求
     */
    private GatewayResponse processWithCancel(RequestContext context, String agentId) {
        // 简化实现：直接处理新请求
        return executeAgent(context, agentId);
    }
    
    /**
     * 合并策略 - 合并短时间内的多个请求
     */
    private GatewayResponse processWithMerge(RequestContext context, String agentId) {
        // 简化实现：直接处理
        return executeAgent(context, agentId);
    }
    
    /**
     * 执行Agent
     */
    private GatewayResponse executeAgent(RequestContext context, String agentId) {
        // 引用计数保护：acquire 增加引用，确保请求期间 Agent 不会被热替换销毁
        AgentEntry entry = agentRegistry.acquire(agentId);
        if (entry == null) {
            return GatewayResponse.notFound("Agent not found: " + agentId);
        }

        try {
            Agent agent = entry.getAgent();
            // 传递 userId 给 Agent
            String response = agent.chat(
                    context.getMessage(),
                    context.getSessionId(),
                    context.getUserId()
            );
            return GatewayResponse.success(response, context.getSessionId(), agentId);
        } catch (Exception e) {
            log.error("Agent执行异常: agentId={}", agentId, e);
            return GatewayResponse.error("Agent execution failed: " + e.getMessage());
        } finally {
            // 释放引用计数，允许热替换安全销毁旧 Agent
            agentRegistry.release(agentId);
        }
    }
    
    /**
     * 异步处理请求
     */
    public CompletableFuture<GatewayResponse> processAsync(GatewayRequest request) {
        return CompletableFuture.supplyAsync(() -> process(request), executorService);
    }
    
    /**
     * 注册domain映射
     */
    public void registerDomain(String domain, String agentId) {
        domainRouter.registerDomain(domain, agentId);
    }
    
    /**
     * 获取网关统计信息
     */
    public GatewayStats getStats() {
        return new GatewayStats(
            sessionLanes.size(),
            rateLimitManager.getStats(),
            config.getGlobalMaxConcurrent()
        );
    }
    
    /**
     * 关闭Gateway
     */
    public void shutdown() {
        log.info("关闭AgentGateway...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        log.info("AgentGateway已关闭");
    }
    
    /**
     * 网关统计信息
     */
    public record GatewayStats(
        int activeSessions,
        RateLimitManager.RateLimitStats rateLimitStats,
        int maxConcurrent
    ) {}
}