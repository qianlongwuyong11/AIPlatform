package com.bitguide.gateway.filter;

import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;

/**
 * 过滤器链接口
 */
public interface FilterChain {
    
    /**
     * 执行下一个过滤器
     * 
     * @param context 请求上下文
     * @return 响应（如果所有过滤器都通过则返回null）
     */
    GatewayResponse doFilter(RequestContext context);
}