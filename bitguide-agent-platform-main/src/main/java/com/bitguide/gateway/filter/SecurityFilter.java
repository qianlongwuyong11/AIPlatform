package com.bitguide.gateway.filter;

import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.gateway.model.RequestContext;
import com.bitguide.gateway.security.SecurityValidator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 安全过滤器
 * 整合多种安全校验策略
 */
@Slf4j
public class SecurityFilter implements GatewayFilter {
    
    private final GatewayConfig config;
    private final List<SecurityValidator> validators;
    
    public SecurityFilter(GatewayConfig config, List<SecurityValidator> validators) {
        this.config = config;
        this.validators = validators;
        
        // 按order排序
        validators.sort((v1, v2) -> Integer.compare(v1.getOrder(), v2.getOrder()));
        
        log.info("安全过滤器初始化: 共{}个校验器", validators.size());
    }
    
    @Override
    public String getName() {
        return "SecurityFilter";
    }
    
    @Override
    public int getOrder() {
        return 150; // 在限流之后执行
    }
    
    @Override
    public boolean isEnabled() {
        return config.isSecurityEnabled();
    }
    
    @Override
    public GatewayResponse filter(RequestContext context, FilterChain chain) {
        String input = context.getMessage();
        
        if (input == null || input.isEmpty()) {
            return GatewayResponse.blocked("输入为空");
        }
        
        // 执行所有校验器
        for (SecurityValidator validator : validators) {
            if (!validator.isEnabled()) {
                continue;
            }
            
            SecurityValidator.ValidationResult result = validator.validate(input);
            
            if (!result.passed()) {
                log.warn("安全校验未通过: validator={}, reason={}, riskLevel={}", 
                        validator.getName(), result.blockedReason(), result.riskLevel());
                return GatewayResponse.blocked(result.blockedReason());
            }
        }
        
        // 继续执行下一个过滤器
        return chain.doFilter(context);
    }
}