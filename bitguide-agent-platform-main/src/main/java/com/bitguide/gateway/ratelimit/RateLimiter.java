package com.bitguide.gateway.ratelimit;

/**
 * 限流器接口
 */
public interface RateLimiter {
    
    /**
     * 尝试获取许可
     * @param key 限流Key
     * @return 是否获取成功
     */
    boolean tryAcquire(String key);
    
    /**
     * 获取等待时间（可选实现)
     * @return 築等待时间(ms)， -1表示不支持等待
     */
    default long getWaitTimeMs() {
        return -1;
    }
    
    /**
     * 获取当前可用令牌数
     */
    default int getTokens() {
        return -1;
    }
}
