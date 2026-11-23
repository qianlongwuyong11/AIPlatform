package com.bitguide.memory;

import lombok.Getter;

/**
 * 交互类型枚举
 */
@Getter
public enum InteractionType {
    
    /**
     * 用户消息
     */
    USER("用户"),
    
    /**
     * 助手响应
     */
    ASSISTANT("助手"),
    
    /**
     * 系统消息
     */
    SYSTEM("系统"),
    
    /**
     * 工具调用
     */
    TOOL_CALL("工具调用"),
    
    /**
     * 工具结果
     */
    TOOL_RESULT("工具结果"),
    
    /**
     * 历史摘要
     */
    SUMMARY("摘要");
    
    private final String displayName;
    
    InteractionType(String displayName) {
        this.displayName = displayName;
    }
}