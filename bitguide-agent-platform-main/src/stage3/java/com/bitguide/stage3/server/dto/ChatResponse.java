package com.bitguide.stage3.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * Agent ID
     */
    private String agentId;
    
    /**
     * 助手响应内容
     */
    private String response;
    
    /**
     * 处理耗时（毫秒）
     */
    private Long durationMs;
    
    /**
     * 是否成功
     */
    @Builder.Default
    private Boolean success = true;
    
    /**
     * 错误码（失败时）
     */
    private String errorCode;
    
    /**
     * 错误消息（失败时）
     */
    private String errorMessage;
    
    /**
     * 创建成功响应
     */
    public static ChatResponse success(String sessionId, String agentId, String response, long durationMs) {
        return ChatResponse.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .response(response)
                .durationMs(durationMs)
                .success(true)
                .build();
    }
    
    /**
     * 创建失败响应
     */
    public static ChatResponse error(String sessionId, String agentId, String errorCode, String errorMessage) {
        return ChatResponse.builder()
                .sessionId(sessionId)
                .agentId(agentId)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}