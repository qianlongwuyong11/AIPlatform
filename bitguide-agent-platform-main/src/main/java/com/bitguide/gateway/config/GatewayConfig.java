package com.bitguide.gateway.config;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway 配置
 */
@Data
public class GatewayConfig {
    
    /**
     * 是否启用认证
     */
    private boolean authEnabled = true;
    
    /**
     * 是否启用安全检查
     */
    private boolean securityEnabled = true;
    
    /**
     * 是否启用限流
     */
    private boolean rateLimitEnabled = true;
    
    /**
     * 是否启用响应缓存
     */
    private boolean cacheEnabled = true;
    
    // ========== 认证配置 ==========
    
    /**
     * 预配置的API Keys (token -> userId)
     */
    private Map<String, String> apiKeys = new HashMap<>();
    
    // ========== 安全配置 ==========
    
    /**
     * 输入最大长度
     */
    private int maxInputLength = 10000;
    
    /**
     * 敏感词列表
     */
    private List<String> sensitiveWords = List.of();
    
    /**
     * 是否启用Prompt注入检测
     */
    private boolean promptInjectionDetection = true;
    
    // ========== 限流配置 ==========
    
    /**
     * 全局QPS限制
     */
    private int globalQps = 1000;
    
    /**
     * 用户QPS限制
     */
    private int userQps = 10;
    
    /**
     * 全局最大并发数
     */
    private int globalMaxConcurrent = 100;
    
    // ========== Lane配置 ==========
    
    /**
     * 每个Lane最大并发数（通常为1，保证串行）
     */
    private int laneMaxConcurrent = 1;
    
    /**
     * Lane队列容量
     */
    private int laneQueueCapacity = 100;
    
    /**
     * Lane任务超时时间（毫秒）
     */
    private long laneTaskTimeoutMs = 60000;
    
    // ========== 消息策略配置 ==========
    
    /**
     * 默认消息策略
     */
    private String defaultStrategy = "followup";
    
    /**
     * Collect策略时间窗（毫秒）
     */
    private long collectTimeWindowMs = 2000;
    
    // ========== 缓存配置 ==========
    
    /**
     * 缓存最大容量
     */
    private int cacheMaxSize = 1000;
    
    /**
     * 缓存过期时间（秒）
     */
    private int cacheTtlSeconds = 300;
    
    /**
     * 创建默认配置
     */
    public static GatewayConfig defaultConfig() {
        GatewayConfig config = new GatewayConfig();
        
        // 添加演示用的API Keys
        config.getApiKeys().put("demo-token-001", "user-001");
        config.getApiKeys().put("demo-token-002", "user-002");
        config.getApiKeys().put("test-key", "test-user");
        
        return config;
    }
}