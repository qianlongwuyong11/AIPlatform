package com.bitguide.gateway.auth;

import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.filter.DefaultFilterChain;
import com.bitguide.gateway.filter.FilterChain;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户认证过滤器（简单实现）
 * 通过预配置的 API Key 验证用户身份
 */
@Slf4j
public class AuthFilter implements com.bitguide.gateway.filter.GatewayFilter {
    
    private final GatewayConfig config;
    
    public AuthFilter(GatewayConfig config) {
        this.config = config;
    }
    
    @Override
    public String getName() {
        return "AuthFilter";
    }
    
    @Override
    public int getOrder() {
        return 100; // 认证过滤器最先执行
    }
    
    @Override
    public boolean isEnabled() {
        return config.isAuthEnabled();
    }
    
    @Override
    public GatewayResponse filter(RequestContext context, FilterChain chain) {
        String token = context.getRequest().getToken();
        
        // 检查 token 是否存在
        if (token == null || token.isEmpty()) {
            log.warn("请求缺少认证token: sessionId={}", context.getSessionId());
            return GatewayResponse.unauthorized("Token is required");
        }
        
        // 验证 token
        String userId = config.getApiKeys().get(token);
        if (userId == null) {
            log.warn("无效的token: token={}, sessionId={}", maskToken(token), context.getSessionId());
            return GatewayResponse.unauthorized("Invalid token");
        }
        
        // 认证成功，设置用户ID
        context.setAuthenticatedUserId(userId);
        log.debug("认证成功: userId={}, sessionId={}", userId, context.getSessionId());
        
        // 继续执行下一个过滤器
        return chain.doFilter(context);
    }
    
    /**
     * 遮蔽 token（日志安全）
     */
    private String maskToken(String token) {
        if (token == null || token.length() < 8) {
            return "***";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}