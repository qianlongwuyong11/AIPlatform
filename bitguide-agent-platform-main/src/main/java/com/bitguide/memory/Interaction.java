package com.bitguide.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 交互记录
 * 表示单次用户或助手的交互
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Interaction {
    
    /**
     * 交互类型
     */
    private InteractionType type;
    
    /**
     * 交互内容
     */
    private String content;
    
    /**
     * 交互时间
     */
    private Instant timestamp;
    
    /**
     * 关联的步骤ID（如果有）
     */
    private String relatedStepId;
    
    /**
     * 简化构造函数
     */
    public Interaction(InteractionType type, String content) {
        this.type = type;
        this.content = content;
        this.timestamp = Instant.now();
    }
    
    /**
     * 判断是否为用户交互
     */
    public boolean isUser() {
        return type == InteractionType.USER;
    }
    
    /**
     * 判断是否为助手交互
     */
    public boolean isAssistant() {
        return type == InteractionType.ASSISTANT;
    }
    
    /**
     * 获取格式化的内容
     */
    public String getFormattedMessage() {
        return String.format("[%s] %s: %s", 
                timestamp != null ? timestamp.toString() : "N/A",
                type != null ? type.getDisplayName() : "Unknown",
                content != null ? content : "");
    }
}