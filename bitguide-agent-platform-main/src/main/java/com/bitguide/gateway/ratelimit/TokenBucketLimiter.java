package com.bitguide.gateway.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流器
 * 寏秒补充令牌， */
public class TokenBucketLimiter implements RateLimiter {
    
    /**
     * 桶容量
     */
    private final long capacity;
    
    /**
     * 当前令牌数
     */
    private final AtomicLong tokens;
    
    /**
     * 每秒补充的令牌数
     */
    private final long refillTokensPerSecond;
    
    /**
     * 上次补充令牌的时间戳
     */
    private final AtomicLong lastRefillTime;
    
    /**
     * @param capacity    桶容量
     * @param qps          每秒补充的令牌数 (即 QPS)
     */
    public TokenBucketLimiter(long capacity, long qps) {
        this.capacity = capacity;
        this.refillTokensPerSecond = qps;
        this.tokens = new AtomicLong(capacity);  // 初始令牌 = 桶容量
        this.lastRefillTime = new AtomicLong(System.nanoTime());
    }
    
    /**
     * 尝试获取许可
     * @param key 限流Key (当前实现中未使用，按用户隔离)
     * @return 是否获取成功
     */
    @Override
    public boolean tryAcquire(String key) {
        // 先补充令牌
        refillTokens();
        
        // 尝试消耗一个令牌
        while (true) {
            long current = tokens.get();
            if (current >= 1) {
                // 使用 CAS 操作确保原子性
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
            return false;
        }
    }
    
    /**
     * 补充令牌（基于时间戳)
     */
    private void refillTokens() {
        long now = System.nanoTime();
        long lastTime = lastRefillTime.get();
        
        // 计算应该补充的令牌数（纳秒转秒：除以 10^9）
        long timePassed = now - lastTime;
        long tokensToAdd = (timePassed * refillTokensPerSecond) / 1_000_000_000L;
        
        if (tokensToAdd > 0) {
            // 先补充令牌，再尝试获取
            long newTokens = Math.min(capacity, tokens.get() + tokensToAdd);
            tokens.set(newTokens);
            lastRefillTime.set(now);
        }
    }
    
    @Override
    public int getTokens() {
        return tokens.intValue();
    }
    
    @Override
    public long getWaitTimeMs() {
        return -1; // 不支持等待
    }
}