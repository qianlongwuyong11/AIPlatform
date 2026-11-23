package com.bitguide.gateway.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 请求上下文
 * 在过滤器链中传递的上下文信息
 */
@Data
@Builder
public class RequestContext {
    
    /**
     * 原始请求
     */
    private GatewayRequest request;
    
    /**
     * 认证后的用户ID
     */
    private String authenticatedUserId;
    
    /**
     * 租户ID（预留）
     */
    private String tenantId;
    
    /**
     * 路由到的Agent ID
     */
    private String routedAgentId;
    
    /**
     * 请求到达时间
     */
    @Builder.Default
    private Instant arrivalTime = Instant.now();
    
    /**
     * 处理开始时间
     */
    private Instant processingStartTime;
    
    /**
     * 是否来自缓存
     */
    @Builder.Default
    private boolean cacheHit = false;
    
    /**
     * 扩展属性
     */
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
    
    /**
     * 设置属性
     */
    public void setAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }
    
    /**
     * 获取属性
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        if (attributes == null) {
            return null;
        }
        return (T) attributes.get(key);
    }
    
    /**
     * 获取会话ID
     */
    public String getSessionId() {
        return request != null ? request.getSessionId() : null;
    }
    
    /**
     * 获取消息内容
     */
    public String getMessage() {
        return request != null ? request.getMessage() : null;
    }
    
    /**
     * 获取用户ID
     */
    public String getUserId() {
        return authenticatedUserId;
    }
    
    /**
     * 标记处理开始
     */
    public void markProcessingStart() {
        this.processingStartTime = Instant.now();
    }
    
    /**
     * 计算处理耗时（毫秒）
     */
    public long calculateProcessingTimeMs() {
        if (processingStartTime == null) {
            return 0;
        }
        return Instant.now().toEpochMilli() - processingStartTime.toEpochMilli();
    }
    
    /**
     * 计算总耗时（从请求到达开始）
     */
    public long calculateTotalTimeMs() {
        if (arrivalTime == null) {
            return 0;
        }
        return Instant.now().toEpochMilli() - arrivalTime.toEpochMilli();
    }
}