package com.bitguide.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Gateway 请求模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayRequest {
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 目标Agent ID（显式指定）
     */
    private String agentId;
    
    /**
     * 领域标识（备选路由方式）
     */
    private String domain;
    
    /**
     * 用户消息内容
     */
    private String message;
    
    /**
     * 认证Token
     */
    private String token;
    
    /**
     * 消息策略
     * followup: FIFO逐条处理
     * collect: 时间窗内聚合
     * steer: 注入到运行中的Agent
     * interrupt: 打断当前执行
     */
    @Builder.Default
    private String strategy = "followup";
    
    /**
     * 扩展参数
     */
    private Map<String, Object> metadata;
    
    /**
     * 请求时间戳
     */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();
    
    /**
     * 获取路由键（优先agentId，其次domain）
     */
    public String getRoutingKey() {
        if (agentId != null && !agentId.isEmpty()) {
            return agentId;
        }
        return domain;
    }
    
    /**
     * 验证请求基本字段
     */
    public boolean isValid() {
        return sessionId != null && !sessionId.isEmpty()
            && message != null && !message.isEmpty()
            && (agentId != null || domain != null);
    }
}