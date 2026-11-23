package com.bitguide.gateway.security;

import com.bitguide.gateway.config.GatewayConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt注入检测校验器
 * 检测常见的Prompt注入攻击模式
 */
@Slf4j
public class PromptInjectionValidator implements SecurityValidator {
    
    private final GatewayConfig config;
    
    /**
     * 恶意的Prompt模式列表
     */
    private static final List<Pattern> MALICIOUS_PATTERNS = new ArrayList<>();
    
    static {
        // "ignore previous instructions"
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous\\s+)?instructions?"));
        // "forget everything"
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)forget\\s+(everything|all)"));
        // "disregard previous"
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)disregard\\s+(previous\\s+)?(instructions?|rules?)"));
        // "override previous"
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)override\\s+(previous\\s+)?(instructions?|rules?)"));
        // "you are not authorized"
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)you are not authorized to"));
        // "you do not have permission"
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)you\\s+do not have permission to"));
        // "你是XXX"角色扮演注入
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)you are (now|a|an)\\s+"));
        // "system:" 伪装系统消息
        MALICIOUS_PATTERNS.add(Pattern.compile("(?i)^system:\\s*"));
        // "忽略上述指令"
        MALICIOUS_PATTERNS.add(Pattern.compile("忽略(上述|之前|所有)?(指令|规则)"));
        // "绕过限制"
        MALICIOUS_PATTERNS.add(Pattern.compile("绕过(所有限制|安全检测)"));
    }
    
    /**
     * Prompt注入关键词列表（用于检测变体)
     */
    private static final List<String> INJECTION_KEYWORDS = List.of(
            "ignore",
            "forget",
            "override",
            "disregard",
            "bypass",
            "skip",
            "inject",
            "绕过",
            "忽略",
            "跳过"
    );
    
    public PromptInjectionValidator(GatewayConfig config) {
        this.config = config;
        initMaliciousPatterns();
    }
    
    @Override
    public String getName() {
        return "PromptInjectionValidator";
    }
    
    @Override
    public int getOrder() {
        return 20; // 在输入长度校验之后执行
    }
    
    @Override
    public boolean isEnabled() {
        return config.isSecurityEnabled() && config.isPromptInjectionDetection();
    }
    
    @Override
    public ValidationResult validate(String input) {
        if (input == null || input.isEmpty()) {
            return ValidationResult.ok();
        }
        
        // 检查恶意模式
        for (Pattern pattern : MALICIOUS_PATTERNS) {
            if (pattern.matcher(input).find()) {
                log.warn("检测到潜在的Prompt注入攻击: pattern={}, input={}", 
                        pattern.pattern(), 
                        input.substring(0, Math.min(50, input.length())));
                return ValidationResult.blocked("检测到潜在的Prompt注入攻击", "HIGH");
            }
        }
        
        // 检查关键词密度（如果输入中包含多个注入关键词，可能是攻击）
        int keywordCount = 0;
        String lowerInput = input.toLowerCase();
        for (String keyword : INJECTION_KEYWORDS) {
            if (lowerInput.contains(keyword.toLowerCase())) {
                keywordCount++;
            }
        }
        
        if (keywordCount >= 3) {
            log.warn("检测到可疑的Prompt注入关键词组合: keywordCount={}, input={}", 
                    keywordCount, 
                    input.substring(0, Math.min(50, input.length())));
            return ValidationResult.blocked("检测到可疑的输入模式", "MEDIUM");
        }
        
        return ValidationResult.ok();
    }
    
    /**
     * 初始化恶意模式列表
     */
    private void initMaliciousPatterns() {
        if (log.isDebugEnabled()) {
            log.debug("初始化 {} 个恶意模式", MALICIOUS_PATTERNS.size());
        }
    }
}