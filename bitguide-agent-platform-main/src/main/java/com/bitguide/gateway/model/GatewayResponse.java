package com.bitguide.gateway.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Gateway 响应模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayResponse {

    /**
     * 是否成功
     */
    @Builder.Default
    private boolean success = true;

    /**
     * 响应内容
     */
    private String content;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 处理的Agent ID
     */
    private String agentId;

    /**
     * 错误码（失败时）
     */
    private String errorCode;

    /**
     * 错误信息（失败时）
     */
    private String errorMessage;

    /**
     * 响应时间戳
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 处理耗时（毫秒）
     */
    private long processingTimeMs;

    /**
     * 是否来自缓存
     */
    @Builder.Default
    private boolean fromCache = false;

    /**
     * 是否触发了压缩
     */
    @Builder.Default
    private boolean historyCompressed = false;

    // ========== 静态工厂方法 ==========

    /**
     * 成功响应
     */
    public static GatewayResponse success(String content, String sessionId, String agentId) {
        return GatewayResponse.builder()
                .success(true)
                .content(content)
                .sessionId(sessionId)
                .agentId(agentId)
                .build();
    }

    /**
     * 成功响应（带处理时间）
     */
    public static GatewayResponse success(String content, String sessionId, String agentId,
                                          long processingTimeMs, boolean fromCache) {
        return GatewayResponse.builder()
                .success(true)
                .content(content)
                .sessionId(sessionId)
                .agentId(agentId)
                .processingTimeMs(processingTimeMs)
                .fromCache(fromCache)
                .build();
    }

    /**
     * 失败响应
     */
    public static GatewayResponse failure(String errorCode, String errorMessage) {
        return GatewayResponse.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 失败响应（带会话信息）
     */
    public static GatewayResponse failure(String errorCode, String errorMessage,
                                          String sessionId, String agentId) {
        return GatewayResponse.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .sessionId(sessionId)
                .agentId(agentId)
                .build();
    }

    /**
     * 认证失败
     */
    public static GatewayResponse unauthorized(String message) {
        return failure("UNAUTHORIZED", message);
    }

    /**
     * 限流
     */
    public static GatewayResponse rateLimited() {
        return failure("RATE_LIMITED", "请求过于频繁，请稍后重试");
    }

    /**
     * 安全检查失败
     */
    public static GatewayResponse blocked(String reason) {
        return failure("BLOCKED", reason);
    }

    /**
     * 路由失败
     */
    public static GatewayResponse routeFailed(String message) {
        return failure("ROUTE_FAILED", message);
    }

    /**
     * 处理超时
     */
    public static GatewayResponse timeout() {
        return failure("TIMEOUT", "请求处理超时");
    }

    /**
     * 内部错误
     */
    public static GatewayResponse internalError(String message) {
        return failure("INTERNAL_ERROR", message);
    }

    /**
     * 未找到
     */
    public static GatewayResponse notFound(String message) {
        return failure("NOT_FOUND", message);
    }

    /**
     * 错误响应
     */
    public static GatewayResponse error(String message) {
        return failure("ERROR", message);
    }
}