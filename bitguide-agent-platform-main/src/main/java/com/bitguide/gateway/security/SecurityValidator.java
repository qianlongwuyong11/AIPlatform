package com.bitguide.gateway.security;

/**
 * 安全校验器接口
 * 实现此接口可以扩展更多的安全校验策略
 */
public interface SecurityValidator {
    
    /**
     * 校验器名称
     */
    String getName();
    
    /**
     * 执行顺序（越小越先执行）
     */
    int getOrder();
    
    /**
     * 执行校验
     * 
     * @param input 输入内容
     * @return 校验结果
     */
    ValidationResult validate(String input);
    
    /**
     * 是否启用
     */
    default boolean isEnabled() {
        return true;
    }
    
    /**
     * 校验结果
     */
    record ValidationResult(
        boolean passed,
        String blockedReason,
        String riskLevel  // LOW, MEDIUM, HIGH
    ) {
        public static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }
        
        public static ValidationResult blocked(String reason) {
            return new ValidationResult(false, reason, "MEDIUM");
        }
        
        public static ValidationResult blocked(String reason, String riskLevel) {
            return new ValidationResult(false, reason, riskLevel);
        }
    }
}