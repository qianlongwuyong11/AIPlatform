package com.bitguide.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenBucketLimiterTest {

    @Test
    void testBasicRateLimiting() {
        // 创建限流器： 桶容量3， qps=1
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, 1);
        
        // 第1次请求应该成功
        assertTrue(limiter.tryAcquire("test"), "第一次请求应该成功");
        
        // 快速发送第2次请求，应该被限流
        assertFalse(limiter.tryAcquire("test"), "第二次请求应该失败");
        
        // 等待一段时间后再次请求，应该成功
        Thread.sleep(1100); // 等待超过1秒
        assertTrue(limiter.tryAcquire("test"), "等待后再次成功");
    }
    
    @Test
    void testMultipleRequestsExhaustively() {
        // 创建限流器: 桶容量3, qps=1
        TokenBucketLimiter limiter = new TokenBucketLimiter(3, 1);
        
        // 快速发送3次请求
        assertTrue(limiter.tryAcquire("test"), "第1次成功");
        assertTrue(limiter.tryAcquire("test"), "第2次成功");
        assertTrue(limiter.tryAcquire("test"), "第3次成功");
        
        // 第4次应该被限流
        assertFalse(limiter.tryAcquire("test"), "第4次失败");
    }
}