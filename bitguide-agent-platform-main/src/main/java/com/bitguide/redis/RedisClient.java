package com.bitguide.redis;

import com.bitguide.config.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 客户端封装
 * 提供分布式锁、缓存、限流等操作
 */
@Slf4j
public class RedisClient {
    
    private final JedisPool jedisPool;
    private final RedisConfig config;
    private volatile boolean initialized = false;
    
    /**
     * 分布式锁的 Lua 脚本（解锁用）
     */
    private static final String UNLOCK_SCRIPT = 
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "return redis.call('del', KEYS[1]) " +
            "else " +
            "return 0 " +
            "end";
    
    /**
     * 令牌桶限流的 Lua 脚本
     */
    private static final String TOKEN_BUCKET_SCRIPT = 
            "local key = KEYS[1] " +
            "local capacity = tonumber(ARGV[1]) " +
            "local rate = tonumber(ARGV[2]) " +
            "local requested = tonumber(ARGV[3]) " +
            "local now = tonumber(ARGV[4]) " +
            "" +
            "local info = redis.call('hmget', key, 'tokens', 'last_refill') " +
            "local tokens = tonumber(info[1]) or capacity " +
            "local lastRefill = tonumber(info[2]) or now " +
            "" +
            "-- 计算令牌补充\n" +
            "local elapsed = math.max(0, now - lastRefill) " +
            "local refill = elapsed * rate / 1000 " +
            "tokens = math.min(capacity, tokens + refill) " +
            "" +
            "-- 尝试获取令牌\n" +
            "local allowed = 0 " +
            "if tokens >= requested then " +
            "tokens = tokens - requested " +
            "allowed = 1 " +
            "end " +
            "" +
            "-- 更新状态\n" +
            "redis.call('hmset', key, 'tokens', tokens, 'last_refill', now) " +
            "redis.call('pexpire', key, math.ceil(capacity / rate * 1000) + 1000) " +
            "" +
            "return allowed";
    
    private static final String MULTI_TOKEN_BUCKET_SCRIPT = """
            local now = tonumber(ARGV[1])
            local balances = {}
            local timestamps = {}
            for i, key in ipairs(KEYS) do
                local qps = tonumber(ARGV[i + 1])
                local state = redis.call('hmget', key, 'tokens', 'last_refill')
                local tokens = tonumber(state[1]) or qps
                local last = tonumber(state[2]) or now
                local available = math.min(qps, tokens + math.max(0, now - last) * qps / 1000)
                if available < 1 then
                    return i
                end
                balances[i] = available - 1
                timestamps[i] = math.max(now, last)
            end

            for i, key in ipairs(KEYS) do
                redis.call('hset', key, 'tokens', balances[i], 'last_refill', timestamps[i])
                -- Capacity equals the refill rate, so an empty bucket fills in one second.
                redis.call('pexpire', key, 2000)
            end
            return 0
            """;
    public RedisClient(RedisConfig config) {
        this.config = config;
        this.jedisPool = createJedisPool(config);
        this.initialized = true;
        log.info("Redis 客户端初始化完成: {}:{}", config.getHost(), config.getPort());
    }
    
    /**
     * 创建 Jedis 连接池
     */
    private JedisPool createJedisPool(RedisConfig config) {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getPool().getMaxTotal());
        poolConfig.setMaxIdle(config.getPool().getMaxIdle());
        poolConfig.setMinIdle(config.getPool().getMinIdle());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setMaxWait(Duration.ofMillis(config.getPool().getConnectionTimeout()));
        
        String password = config.getPassword();
        if (password != null && password.isEmpty()) {
            password = null;
        }
        
        return new JedisPool(
                poolConfig,
                config.getHost(),
                config.getPort(),
                config.getPool().getReadTimeout(),
                password,
                config.getDatabase()
        );
    }
    
    /**
     * 获取 Jedis 连接
     */
    public Jedis getResource() {
        return jedisPool.getResource();
    }
    
    /**
     * 执行操作
     */
    public <T> T execute(RedisOperation<T> operation) {
        try (Jedis jedis = getResource()) {
            return operation.execute(jedis);
        } catch (Exception e) {
            log.error("Redis 操作失败", e);
            throw new RuntimeException("Redis 操作失败: " + e.getMessage(), e);
        }
    }
    
    // ==================== 字符串操作 ====================
    
    /**
     * 设置字符串值
     */
    public void set(String key, String value) {
        execute(jedis -> {
            jedis.set(key, value);
            return null;
        });
    }
    
    /**
     * 设置字符串值（带过期时间）
     */
    public void setex(String key, String value, long expireMs) {
        execute(jedis -> {
            jedis.psetex(key, expireMs, value);
            return null;
        });
    }
    
    /**
     * 获取字符串值
     */
    public String get(String key) {
        return execute(jedis -> jedis.get(key));
    }
    
    /**
     * 设置如果不存在
     */
    public boolean setnx(String key, String value, long expireMs) {
        return execute(jedis -> {
            SetParams params = SetParams.setParams().nx().px(expireMs);
            return "OK".equals(jedis.set(key, value, params));
        });
    }
    
    /**
     * 删除 key
     */
    public boolean del(String key) {
        return execute(jedis -> jedis.del(key) > 0);
    }
    
    /**
     * 检查 key 是否存在
     */
    public boolean exists(String key) {
        return execute(jedis -> jedis.exists(key));
    }
    
    /**
     * 设置 key 过期时间
     */
    public boolean expire(String key, long expireMs) {
        return execute(jedis -> jedis.pexpire(key, expireMs) > 0);
    }
    
    // ==================== Hash 操作 ====================
    
    /**
     * 设置 Hash 字段
     */
    public void hset(String key, String field, String value) {
        execute(jedis -> {
            jedis.hset(key, field, value);
            return null;
        });
    }
    
    /**
     * 批量设置 Hash 字段
     */
    public void hmset(String key, Map<String, String> hash) {
        execute(jedis -> {
            jedis.hset(key, hash);
            return null;
        });
    }
    
    /**
     * 获取 Hash 字段
     */
    public String hget(String key, String field) {
        return execute(jedis -> jedis.hget(key, field));
    }
    
    /**
     * 获取 Hash 所有字段
     */
    public Map<String, String> hgetAll(String key) {
        return execute(jedis -> jedis.hgetAll(key));
    }
    
    /**
     * 删除 Hash 字段
     */
    public long hdel(String key, String... fields) {
        return execute(jedis -> jedis.hdel(key, fields));
    }
    
    // ==================== 分布式锁 ====================
    
    /**
     * 尝试获取分布式锁
     * 
     * @param lockKey 锁的 key
     * @param requestId 请求 ID（用于标识锁的持有者）
     * @param expireMs 锁的过期时间（毫秒）
     * @return 是否成功获取锁
     */
    public boolean tryLock(String lockKey, String requestId, long expireMs) {
        return execute(jedis -> {
            SetParams params = SetParams.setParams().nx().px(expireMs);
            return "OK".equals(jedis.set(lockKey, requestId, params));
        });
    }
    
    /**
     * 尝试获取分布式锁（带等待时间）
     * 
     * @param lockKey 锁的 key
     * @param requestId 请求 ID
     * @param expireMs 锁的过期时间
     * @param waitMs 最大等待时间
     * @return 是否成功获取锁
     */
    public boolean tryLock(String lockKey, String requestId, long expireMs, long waitMs) {
        long startTime = System.currentTimeMillis();
        long retryInterval = 50; // 重试间隔
        
        while (System.currentTimeMillis() - startTime < waitMs) {
            if (tryLock(lockKey, requestId, expireMs)) {
                return true;
            }
            
            try {
                TimeUnit.MILLISECONDS.sleep(retryInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * 释放分布式锁
     * 
     * @param lockKey 锁的 key
     * @param requestId 请求 ID
     * @return 是否成功释放
     */
    public boolean unlock(String lockKey, String requestId) {
        return execute(jedis -> {
            Object result = jedis.eval(UNLOCK_SCRIPT, 
                    Collections.singletonList(lockKey), 
                    Collections.singletonList(requestId));
            return Long.valueOf(1).equals(result);
        });
    }
    
    // ==================== 限流操作 ====================
    
    /**
     * 令牌桶限流 - 尝试获取令牌
     * 
     * @param key 限流 key
     * @param capacity 桶容量
     * @param rate 令牌生成速率（每秒）
     * @param requested 请求的令牌数
     * @return 是否允许
     */
    public boolean tryAcquireToken(String key, int capacity, double rate, int requested) {
        if (capacity <= 0 || !Double.isFinite(rate) || rate <= 0 || requested <= 0 || requested > capacity) {
            return false;
        }
        try (Jedis jedis = getResource()) {
            Object result = jedis.eval(TOKEN_BUCKET_SCRIPT, Collections.singletonList(key + ":token_bucket"),
                    Arrays.asList(Integer.toString(capacity), Double.toString(rate),
                            Integer.toString(requested), Long.toString(System.currentTimeMillis())));
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            log.error("Redis 限流操作失败", e);
            return false;
        }
    }
    
    /** 桶容量和每秒补充量均为 qps。 */
    public record TokenBucket(String key, int qps) {}

    /** 从每个桶各申请一个令牌，额度不足时整组拒绝。 */
    public boolean tryAcquireTokens(List<TokenBucket> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return false;
        }
        List<String> keys = new ArrayList<>(buckets.size());
        List<String> args = new ArrayList<>(buckets.size() + 1);
        Set<String> seen = new HashSet<>();
        args.add(Long.toString(System.currentTimeMillis()));
        for (TokenBucket bucket : buckets) {
            if (bucket == null || bucket.key() == null || bucket.qps() <= 0 || !seen.add(bucket.key())) {
                return false;
            }
            keys.add(bucket.key() + ":token_bucket");
            args.add(Integer.toString(bucket.qps()));
        }
        try (Jedis jedis = getResource()) {
            long denied = (Long) jedis.eval(MULTI_TOKEN_BUCKET_SCRIPT, keys, args);
            if (denied != 0) {
                log.debug("令牌不足: key={}", keys.get(Math.toIntExact(denied) - 1));
            }
            return denied == 0;
        } catch (Exception e) {
            log.error("Redis 多桶限流操作失败", e);
            return false;
        }
    }
    /**
     * 简单计数限流
     * 
     * @param key 限流 key
     * @param limit 限制数量
     * @param windowMs 时间窗口（毫秒）
     * @return 是否允许
     */
    public boolean tryAcquire(String key, int limit, long windowMs) {
        try (Jedis jedis = getResource()) {
            long current = jedis.incr(key);
            if (current == 1) {
                jedis.pexpire(key, windowMs);
            }
            return current <= limit;
        } catch (Exception e) {
            log.error("Redis 限流操作失败", e);
            return false;
        }
    }
    
    // ==================== 生命周期 ====================
    
    /**
     * 检查连接是否正常
     */
    public boolean isHealthy() {
        try {
            return execute(jedis -> "PONG".equals(jedis.ping()));
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 关闭连接池
     */
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            log.info("Redis 连接池已关闭");
        }
    }
    
    /**
     * Redis 操作接口
     */
    @FunctionalInterface
    public interface RedisOperation<T> {
        T execute(Jedis jedis);
    }
}
