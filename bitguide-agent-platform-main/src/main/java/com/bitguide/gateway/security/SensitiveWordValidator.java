package com.bitguide.gateway.security;

import com.bitguide.gateway.config.GatewayConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 敏感词过滤校验器
 */
@Slf4j
public class SensitiveWordValidator implements SecurityValidator {
    
    private final GatewayConfig config;
    private List<String> sensitiveWords;
    
    public SensitiveWordValidator(GatewayConfig config) {
        this.config = config;
        // 初始化敏感词列表（可以扩展)
        this.sensitiveWords = config.getSensitiveWords() != null ? config.getSensitiveWords() : List.of();
    }
    
    @Override
    public String getName() {
        return "SensitiveWordValidator";
    }
    
    @Override
    public int getOrder() {
        return 30; // 在输入长度校验之后执行
    }
    
    @Override
    public boolean isEnabled() {
        return config.isSecurityEnabled() && !sensitiveWords.isEmpty();
    }
    
    @Override
    public ValidationResult validate(String input) {
        if (input == null || input.isEmpty()) {
            return ValidationResult.ok();
        }
        
        // 检查敏感词
        String lowerInput = input.toLowerCase();
        for (String word : sensitiveWords) {
            if (lowerInput.contains(word.toLowerCase())) {
                log.warn("检测到敏感词: word={}", word);
                return ValidationResult.blocked("包含敏感词: '" + word + "'", "HIGH");
            }
        }
        
        return ValidationResult.ok();
    }
}