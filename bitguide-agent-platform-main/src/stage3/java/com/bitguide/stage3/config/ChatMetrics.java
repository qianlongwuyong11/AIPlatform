package com.bitguide.stage3.config;

import com.bitguide.gateway.distributed.DistributedAgentGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Prometheus 自定义业务指标
 *
 * 指标设计（教学演示）：
 *
 * | 指标名 | 类型 | 说明 |
 * |--------|------|------|
 * | bitguide_chat_requests_total | Counter | 聊天请求总数（按 agentId, status 分类） |
 * | bitguide_chat_duration_seconds | Timer   | 聊天响应耗时分布 |
 * | bitguide_active_sessions     | Gauge   | 当前活跃会话数 |
 * | bitguide_active_lanes        | Gauge   | 当前并发 Lane 数 |
 * | bitguide_rate_limit_rejected_total | Counter | 限流拒绝次数 |
 *
 * 自动指标（Actuator 免费赠送）：
 * - jvm_memory_used_bytes     — JVM 内存使用
 * - jvm_threads_live_threads  — 活跃线程数
 * - http_server_requests_seconds — HTTP 请求 QPS/延迟
 * - system_cpu_usage          — CPU 使用率
 */
@Slf4j
@Component
public class ChatMetrics {

    private final Counter chatRequestSuccessCounter;
    private final Counter chatRequestErrorCounter;
    private final Counter rateLimitRejectedCounter;
    private final Timer chatDurationTimer;

    public ChatMetrics(@Autowired(required = false) MeterRegistry registry,
                       @Autowired(required = false) @Nullable DistributedAgentGateway gateway) {
        if (registry == null) {
            log.info("    - Prometheus 指标未启用（MeterRegistry 不可用）");
            chatRequestSuccessCounter = null;
            chatRequestErrorCounter = null;
            rateLimitRejectedCounter = null;
            chatDurationTimer = null;
            return;
        }

        // 聊天请求成功计数
        chatRequestSuccessCounter = Counter.builder("bitguide_chat_requests_total")
                .description("聊天请求总数")
                .tag("status", "success")
                .register(registry);

        // 聊天请求失败计数
        chatRequestErrorCounter = Counter.builder("bitguide_chat_requests_total")
                .description("聊天请求总数")
                .tag("status", "error")
                .register(registry);

        // 限流拒绝计数
        rateLimitRejectedCounter = Counter.builder("bitguide_rate_limit_rejected_total")
                .description("限流拒绝次数")
                .register(registry);

        // 聊天响应耗时
        chatDurationTimer = Timer.builder("bitguide_chat_duration_seconds")
                .description("聊天响应耗时分布")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry);

        // 活跃会话数（Gauge — 自动采样）
        if (gateway != null) {
            registry.gauge("bitguide_active_sessions", gateway, g -> g.getActiveSessionCount());
            registry.gauge("bitguide_active_lanes", gateway, g -> g.getActiveLaneCount());
        }

        log.info("    ✓ Prometheus 业务指标注册完成（5 个自定义指标）");
    }

    /**
     * 记录成功的聊天请求
     */
    public void recordChatSuccess(String agentId, long durationMs) {
        if (chatDurationTimer != null) {
            chatDurationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
        if (chatRequestSuccessCounter != null) {
            chatRequestSuccessCounter.increment();
        }
    }

    /**
     * 记录失败的聊天请求
     */
    public void recordChatError(long durationMs) {
        if (chatDurationTimer != null) {
            chatDurationTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
        if (chatRequestErrorCounter != null) {
            chatRequestErrorCounter.increment();
        }
    }

    /**
     * 记录限流拒绝
     */
    public void recordRateLimitRejected() {
        if (rateLimitRejectedCounter != null) {
            rateLimitRejectedCounter.increment();
        }
    }
}
