package com.bitguide.gateway.filter;

import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;

/**
 * Gateway 过滤器接口
 * 所有请求都需要经过过滤器链处理
 */
public interface GatewayFilter {
    
    /**
     * 过滤器名称
     */
    String getName();
    
    /**
     * 执行顺序（越小越先执行）
     */
    int getOrder();
    
    /**
     * 执行过滤
     * 
     * @param context 请求上下文
     * @param chain 过滤器链
     * @return 如果返回非null，则中断链并返回该响应；如果返回null，继续执行下一个过滤器
     */
    GatewayResponse filter(RequestContext context, FilterChain chain);
    
    /**
     * 是否启用
     */
    default boolean isEnabled() {
        return true;
    }
}