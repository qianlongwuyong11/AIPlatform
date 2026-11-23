package com.bitguide.config;

import lombok.Data;

import java.util.Map;

/**
 * Redis 配置类
 * 用于分布式会话、限流和Lane管理
 */
@Data
public class RedisConfig {
    
    /**
     * 是否启用Redis
     */
    private boolean enabled = false;
    
    /**
     * Redis 主机地址
     */
    private String host = "localhost";
    
    /**
     * Redis 端口
     */
    private int port = 6379;
    
    /**
     * Redis 密码
     */
    private String password = "";
    
    /**
     * Redis 数据库索引
     */
    private int database = 0;
    
    /**
     * 连接池配置
     */
    private PoolConfig pool = new PoolConfig();
    
    /**
     * Lane（车道）配置
     */
    private LaneConfig lane = new LaneConfig();
    
    /**
     * 限流配置
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();
    
    /**
     * 会话配置
     */
    private SessionConfig session = new SessionConfig();
    
    @Data
    public static class PoolConfig {
        /**
         * 最大连接数
         */
        private int maxTotal = 100;
        
        /**
         * 最大空闲连接数
         */
        private int maxIdle = 50;
        
        /**
         * 最小空闲连接数
         */
        private int minIdle = 10;
        
        /**
         * 连接超时时间（毫秒）
         */
        private int connectionTimeout = 5000;
        
        /**
         * 读取超时时间（毫秒）
         */
        private int readTimeout = 10000;
    }
    
    @Data
    public static class LaneConfig {
        /**
         * Lane Key 前缀
         */
        private String keyPrefix = "bitguide:lane:";
        
        /**
         * 锁超时时间（毫秒）
         */
        private long lockTimeoutMs = 60000;
    }
    
    @Data
    public static class RateLimitConfig {
        /**
         * 限流 Key 前缀
         */
        private String keyPrefix = "bitguide:ratelimit:";
    }
    
    @Data
    public static class SessionConfig {
        /**
         * 会话 Key 前缀
         */
        private String keyPrefix = "bitguide:session:";
        
        /**
         * 会话超时时间（毫秒）
         */
        private long timeoutMs = 3600000; // 1小时
    }
    
    /**
     * 从 YAML Map 解析配置
     */
    @SuppressWarnings("unchecked")
    public static RedisConfig fromMap(Map<String, Object> map) {
        if (map == null) {
            return new RedisConfig();
        }
        
        RedisConfig config = new RedisConfig();
        config.setEnabled(getBoolean(map, "enabled", false));
        config.setHost(getString(map, "host", "localhost"));
        config.setPort(getInt(map, "port", 6379));
        config.setPassword(getString(map, "password", ""));
        config.setDatabase(getInt(map, "database", 0));
        
        // 解析连接池配置
        Map<String, Object> poolMap = (Map<String, Object>) map.get("pool");
        if (poolMap != null) {
            PoolConfig pool = config.getPool();
            pool.setMaxTotal(getInt(poolMap, "maxTotal", 100));
            pool.setMaxIdle(getInt(poolMap, "maxIdle", 50));
            pool.setMinIdle(getInt(poolMap, "minIdle", 10));
            pool.setConnectionTimeout(getInt(poolMap, "connectionTimeout", 5000));
            pool.setReadTimeout(getInt(poolMap, "readTimeout", 10000));
        }
        
        // 解析 Lane 配置
        Map<String, Object> laneMap = (Map<String, Object>) map.get("lane");
        if (laneMap != null) {
            LaneConfig lane = config.getLane();
            lane.setKeyPrefix(getString(laneMap, "keyPrefix", "bitguide:lane:"));
            lane.setLockTimeoutMs(getLong(laneMap, "lockTimeoutMs", 60000));
        }
        
        // 解析限流配置
        Map<String, Object> rateLimitMap = (Map<String, Object>) map.get("rateLimit");
        if (rateLimitMap != null) {
            RateLimitConfig rateLimit = config.getRateLimit();
            rateLimit.setKeyPrefix(getString(rateLimitMap, "keyPrefix", "bitguide:ratelimit:"));
        }
        
        // 解析会话配置
        Map<String, Object> sessionMap = (Map<String, Object>) map.get("session");
        if (sessionMap != null) {
            SessionConfig session = config.getSession();
            session.setKeyPrefix(getString(sessionMap, "keyPrefix", "bitguide:session:"));
            session.setTimeoutMs(getLong(sessionMap, "timeoutMs", 3600000));
        }
        
        return config;
    }
    
    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        return value != null ? Boolean.TRUE.equals(value) : defaultValue;
    }
    
    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }
    
    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
    
    private static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }
}