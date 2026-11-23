package com.bitguide.gateway.distributed;

import com.bitguide.config.RedisConfig;
import com.bitguide.redis.RedisClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式 Lane 管理器
 * 使用 Redis 实现跨实例的 Lane 锁
 * 
 * 每个 Session 独占一个 Lane，实现串行处理
 */
@Slf4j
public class RedisLaneManager {
    
    private final RedisClient redisClient;
    private final RedisConfig.LaneConfig laneConfig;
    
    /**
     * 本地请求 ID 缓存（用于解锁时验证）
     */
    private final Map<String, String> requestIdCache = new ConcurrentHashMap<>();
    
    public RedisLaneManager(RedisClient redisClient, RedisConfig config) {
        this.redisClient = redisClient;
        this.laneConfig = config.getLane();
        log.info("分布式 Lane 管理器初始化完成, keyPrefix={}", laneConfig.getKeyPrefix());
    }
    
    /**
     * 获取 Lane 锁 Key
     */
    private String getLaneKey(String sessionId) {
        return laneConfig.getKeyPrefix() + sessionId;
    }
    
    /**
     * 尝试进入 Lane
     * 
     * @param sessionId 会话 ID
     * @return 是否成功进入
     */
    public boolean tryEnter(String sessionId) {
        String laneKey = getLaneKey(sessionId);
        String requestId = UUID.randomUUID().toString();
        
        // 尝试获取锁
        boolean acquired = redisClient.tryLock(laneKey, requestId, laneConfig.getLockTimeoutMs());
        
        if (acquired) {
            requestIdCache.put(sessionId, requestId);
            log.debug("成功进入 Lane: sessionId={}, requestId={}", sessionId, requestId);
        } else {
            log.debug("Lane 被占用: sessionId={}", sessionId);
        }
        
        return acquired;
    }
    
    /**
     * 尝试进入 Lane（带等待时间）
     * 
     * @param sessionId 会话 ID
     * @param waitMs 最大等待时间
     * @return 是否成功进入
     */
    public boolean tryEnter(String sessionId, long waitMs) {
        String laneKey = getLaneKey(sessionId);
        String requestId = UUID.randomUUID().toString();
        
        // 尝试获取锁（带等待）
        boolean acquired = redisClient.tryLock(laneKey, requestId, laneConfig.getLockTimeoutMs(), waitMs);
        
        if (acquired) {
            requestIdCache.put(sessionId, requestId);
            log.debug("成功进入 Lane: sessionId={}, requestId={}, waitMs={}", sessionId, requestId, waitMs);
        } else {
            log.debug("Lane 等待超时: sessionId={}, waitMs={}", sessionId, waitMs);
        }
        
        return acquired;
    }
    
    /**
     * 离开 Lane（释放锁）
     * 
     * @param sessionId 会话 ID
     */
    public void leave(String sessionId) {
        String laneKey = getLaneKey(sessionId);
        String requestId = requestIdCache.remove(sessionId);
        
        if (requestId != null) {
            boolean released = redisClient.unlock(laneKey, requestId);
            if (released) {
                log.debug("成功离开 Lane: sessionId={}, requestId={}", sessionId, requestId);
            } else {
                log.warn("离开 Lane 失败（锁可能已过期）: sessionId={}, requestId={}", sessionId, requestId);
            }
        } else {
            // 没有找到 requestId，可能锁已过期或从未获取
            log.debug("离开 Lane: sessionId={} (无 requestId)", sessionId);
        }
    }
    
    /**
     * 检查 Lane 是否被占用
     * 
     * @param sessionId 会话 ID
     * @return 是否被占用
     */
    public boolean isOccupied(String sessionId) {
        String laneKey = getLaneKey(sessionId);
        return redisClient.exists(laneKey);
    }
    
    /**
     * 强制清除 Lane（谨慎使用）
     * 
     * @param sessionId 会话 ID
     * @return 是否成功清除
     */
    public boolean forceClear(String sessionId) {
        String laneKey = getLaneKey(sessionId);
        requestIdCache.remove(sessionId);
        return redisClient.del(laneKey);
    }
    
    /**
     * 获取 Lane 剩余时间
     * 
     * @param sessionId 会话 ID
     * @return 剩余时间（毫秒），-1 表示不存在或无过期时间
     */
    public long getRemainingTime(String sessionId) {
        // Jedis 不直接支持 TTL 返回毫秒，这里简化处理
        return redisClient.exists(getLaneKey(sessionId)) ? laneConfig.getLockTimeoutMs() : -1;
    }
    
    /**
     * 获取活跃 Lane 数量（本地缓存）
     */
    public int getActiveLaneCount() {
        return requestIdCache.size();
    }
}