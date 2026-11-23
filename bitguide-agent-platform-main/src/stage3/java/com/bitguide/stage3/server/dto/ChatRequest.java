package com.bitguide.stage3.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    
    /**
     * 会话 ID（可选，不传则自动生成）
     */
    private String sessionId;
    
    /**
     * 用户 ID（可选）
     */
    private String userId;
    
    /**
     * Agent ID（必填）
     */
    private String agentId;
    
    /**
     * 用户消息（必填）
     */
    private String message;
    
    /**
     * 是否流式返回（可选，默认 false）
     */
    @Builder.Default
    private Boolean stream = false;
}