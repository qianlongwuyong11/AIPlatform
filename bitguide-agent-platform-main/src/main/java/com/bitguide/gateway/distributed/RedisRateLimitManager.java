package com.bitguide.gateway.distributed;

import com.bitguide.config.RedisConfig;
import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import com.bitguide.redis.RedisClient;
import com.bitguide.redis.RedisClient.TokenBucket;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 分布式限流管理器
 * 使用 Redis 实现跨实例的限流
 */
@Slf4j
public class RedisRateLimitManager {
    
    private final RedisClient redisClient;
    private final RedisConfig.RateLimitConfig rateLimitConfig;
    private final GatewayConfig gatewayConfig;
    
    public RedisRateLimitManager(RedisClient redisClient, RedisConfig config, GatewayConfig gatewayConfig) {
        this.redisClient = redisClient;
        this.rateLimitConfig = config.getRateLimit();
        this.gatewayConfig = gatewayConfig;
        log.info("分布式限流管理器初始化完成, keyPrefix={}", rateLimitConfig.getKeyPrefix());
    }
    
    /**
     * 执行限流检查
     * 
     * @param context 请求上下文
     * @return 如果返回null表示通过，否则返回限流响应
     */
    public GatewayResponse check(RequestContext context) {
        if (!gatewayConfig.isRateLimitEnabled()) {
            return null; // 限流未启用
        }
        
        String userId = context.getAuthenticatedUserId();
        String agentId = context.getRoutedAgentId();
        
        // Redis Cluster uses the shared hash tag to route every bucket in this EVAL to one slot.
        String prefix = rateLimitConfig.getKeyPrefix() + "{gateway-rate}:";
        List<TokenBucket> buckets = new ArrayList<>(3);
        buckets.add(new TokenBucket(prefix + "global", gatewayConfig.getGlobalQps()));
        if (userId != null) {
            buckets.add(new TokenBucket(prefix + "user:" + userId, gatewayConfig.getUserQps()));
        }
        if (agentId != null && gatewayConfig.getGlobalMaxConcurrent() > 50) {
            buckets.add(new TokenBucket(prefix + "agent:" + agentId, 5));
        }

        if (!redisClient.tryAcquireTokens(buckets)) {
            log.warn("请求限流: userId={}, agentId={}", userId, agentId);
            return GatewayResponse.rateLimited();
        }
        log.debug("限流检查通过: userId={}, agentId={}", userId, agentId);
        return null;
    }
    /**
     * 获取限流状态
     */
    public RateLimitStats getStats() {
        return new RateLimitStats(0, 0, 0, 0);
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
