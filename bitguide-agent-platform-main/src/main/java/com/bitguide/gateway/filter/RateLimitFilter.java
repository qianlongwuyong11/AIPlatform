package com.bitguide.gateway.filter;

import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import com.bitguide.gateway.ratelimit.RateLimitManager;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 限流过滤器
 */
@Slf4j
public class RateLimitFilter implements GatewayFilter {
    
    private final RateLimitManager rateLimitManager;
    private final GatewayConfig config;
    
    public RateLimitFilter(RateLimitManager rateLimitManager, GatewayConfig config) {
        this.rateLimitManager = rateLimitManager;
        this.config = config;
    }
    
    @Override
    public String getName() {
        return "RateLimitFilter";
    }
    
    @Override
    public int getOrder() {
        return 200; // 在认证之后执行
    }
    
    @Override
    public boolean isEnabled() {
        return config.isRateLimitEnabled();
    }
    
    @Override
    public GatewayResponse filter(RequestContext context, FilterChain chain) {
        // 执行限流检查
        GatewayResponse result = rateLimitManager.check(context);
        if (result != null) {
            return result;
        }
        
        // 继续执行下一个过滤器
        return chain.doFilter(context);
    }
}