package com.bitguide.gateway.router;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 路由结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutingResult {
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 目标Agent ID
     */
    private String agentId;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * 路由策略
     */
    private String strategy;
    
    // ========== 静态工厂方法 ==========
    
    /**
     * 成功路由
     */
    public static RoutingResult success(String agentId) {
        return RoutingResult.builder()
                .success(true)
                .agentId(agentId)
                .strategy("explicit")
                .build();
    }
    
    /**
     * 成功路由（带策略信息）
     */
    public static RoutingResult success(String agentId, String strategy) {
        return RoutingResult.builder()
                .success(true)
                .agentId(agentId)
                .strategy(strategy)
                .build();
    }
    
    /**
     * 路由失败
     */
    public static RoutingResult failure(String errorMessage) {
        return RoutingResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
    
    /**
     * Agent不存在
     */
    public static RoutingResult notFound(String agentId) {
        return RoutingResult.builder()
                .success(false)
                .errorMessage("Agent not found: " + agentId)
                .build();
    }
}