package com.bitguide.gateway.security;

import com.bitguide.gateway.config.GatewayConfig;

/**
 * 输入长度校验器
 */
public class InputLengthValidator implements SecurityValidator {
    
    private final GatewayConfig config;
    
    public InputLengthValidator(GatewayConfig config) {
        this.config = config;
    }
    
    @Override
    public String getName() {
        return "InputLengthValidator";
    }
    
    @Override
    public int getOrder() {
        return 10; // 最优先执行
    }
    
    @Override
    public boolean isEnabled() {
        return config.isSecurityEnabled();
    }
    
    @Override
    public ValidationResult validate(String input) {
        if (input == null) {
            return ValidationResult.ok();
        }
        
        if (input.length() > config.getMaxInputLength()) {
            return ValidationResult.blocked(
                    "Input too long, max allowed length is " + config.getMaxInputLength());
        }
        
        return ValidationResult.ok();
    }
}