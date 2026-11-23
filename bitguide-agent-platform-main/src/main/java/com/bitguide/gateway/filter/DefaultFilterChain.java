package com.bitguide.gateway.filter;

import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 默认过滤器链实现
 * 
 * 注意：每次调用 doFilter 都会从头开始遍历过滤器链，
 * 确保 FilterChain 实例可以被安全地重用。
 */
@Slf4j
public class DefaultFilterChain implements FilterChain {
    
    private final List<GatewayFilter> filters;
    
    public DefaultFilterChain(List<GatewayFilter> filters) {
        // 按order排序
        this.filters = new ArrayList<>(filters);
        this.filters.sort(Comparator.comparingInt(GatewayFilter::getOrder));
        
        log.info("过滤器链初始化完成，共{}个过滤器: {}", 
                this.filters.size(),
                this.filters.stream().map(GatewayFilter::getName).toList());
    }
    
    @Override
    public GatewayResponse doFilter(RequestContext context) {
        // 使用内部类维护每次调用的状态，确保 FilterChain 可重用
        return new FilterChainExecution().execute(context);
    }
    
    /**
     * 内部类：单次过滤器链执行
     * 每次调用 doFilter 创建新实例，维护独立的执行状态
     */
    private class FilterChainExecution implements FilterChain {
        private int currentIndex = 0;
        
        public GatewayResponse execute(RequestContext context) {
            return doFilter(context);
        }
        
        @Override
        public GatewayResponse doFilter(RequestContext context) {
            if (currentIndex >= filters.size()) {
                // 所有过滤器都通过了
                return null;
            }
            
            GatewayFilter currentFilter = filters.get(currentIndex++);
            
            // 跳过未启用的过滤器
            if (!currentFilter.isEnabled()) {
                log.debug("过滤器 {} 未启用，跳过", currentFilter.getName());
                return doFilter(context);
            }
            
            log.debug("执行过滤器: {}", currentFilter.getName());
            return currentFilter.filter(context, this);
        }
    }
}