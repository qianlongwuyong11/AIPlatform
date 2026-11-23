package com.bitguide.gateway.ratelimit;

import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流管理器
 * 支持多维度限流：全局、用户、Agent
 */
@Slf4j
public class RateLimitManager {
    
    private final GatewayConfig config;
    
    /**
     * 全局限流器
     */
    private final RateLimiter globalLimiter;
    
    /**
     * 用户限流器缓存
     */
    private final Map<String, RateLimiter> userLimiters = new ConcurrentHashMap<>();
    
    /**
     * Agent限流器缓存（可选)
     */
    private final Map<String, RateLimiter> agentLimiters = new ConcurrentHashMap<>();
    
    public RateLimitManager(GatewayConfig config) {
        this.config = config;
        
        // 初始化全局限流器
        this.globalLimiter = new TokenBucketLimiter(
                config.getGlobalQps(), 
                config.getGlobalQps()
        );
        
        log.info("限流管理器初始化: globalQps={}, userQps={}", 
                config.getGlobalQps(), config.getUserQps());
    }
    
    /**
     * 执行限流检查
     * 
     * @param context 请求上下文
     * @return 如果返回null表示通过，否则返回限流响应
     */
    public GatewayResponse check(RequestContext context) {
        if (!config.isRateLimitEnabled()) {
            return null; // 限流未启用
        }
        
        String userId = context.getAuthenticatedUserId();
        String agentId = context.getRoutedAgentId();
        
        // 1. 检查全局限流
        if (!globalLimiter.tryAcquire("global")) {
            log.warn("全局限流触发: userId={}", userId);
            return GatewayResponse.rateLimited();
        }
        
        // 2. 检查用户限流
        if (userId != null) {
            RateLimiter userLimiter = userLimiters.computeIfAbsent(
                userId, 
                k -> new TokenBucketLimiter(config.getUserQps(), config.getUserQps())
            );
            
            if (!userLimiter.tryAcquire(userId)) {
                log.warn("用户限流触发: userId={}", userId);
                return GatewayResponse.rateLimited();
            }
        }
        
        // 3. 检查Agent限流（可选)
        if (agentId != null && config.getGlobalMaxConcurrent() > 50) {
            RateLimiter agentLimiter = agentLimiters.computeIfAbsent(
                agentId, 
                k -> new TokenBucketLimiter(5, 5) // Agent默认5 QPS
            );
            
            if (!agentLimiter.tryAcquire(agentId)) {
                log.warn("Agent限流触发: agentId={}", agentId);
                return GatewayResponse.rateLimited();
            }
        }
        
        // 通过限流检查
        log.debug("限流检查通过: userId={}, agentId={}", userId, agentId);
        return null;
    }
    
    /**
     * 获取限流状态
     */
    public RateLimitStats getStats() {
        int totalRequests = 0;
        int globalRemaining = globalLimiter.getTokens();
        int userCount = userLimiters.size();
        int agentCount = agentLimiters.size();
        
        return new RateLimitStats(totalRequests, globalRemaining, userCount, agentCount);
    }
    
    /**
     * 限流状态
     */
    public record RateLimitStats(
        int totalRequests,
        int globalRemaining,
        int activeUsers,
        int activeAgents
    ) {}
}