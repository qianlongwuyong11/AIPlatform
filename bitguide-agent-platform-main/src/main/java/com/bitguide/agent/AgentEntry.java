package com.bitguide.agent;

import com.bitguide.config.AgentConfig;
import lombok.Getter;

import java.time.Instant;

/**
 * Agent 注册条目
 * 包含 Agent 实例及其元数据
 */
@Getter
public class AgentEntry {
    
    /**
     * Agent 实例
     */
    private final Agent agent;
    
    /**
     * Agent 配置
     */
    private final AgentConfig config;
    
    /**
     * 配置版本（用于检测配置变更）
     */
    private final String configVersion;
    
    /**
     * 注册时间
     */
    private final Instant registeredAt;
    
    /**
     * 最后更新时间
     */
    private volatile Instant lastUpdatedAt;
    
    /**
     * 引用计数（正在处理的会话数）
     */
    private volatile int refCount = 0;
    
    /**
     * 是否标记为待删除
     */
    private volatile boolean markedForDeletion = false;
    
    public AgentEntry(Agent agent, AgentConfig config) {
        this.agent = agent;
        this.config = config;
        this.configVersion = generateConfigVersion(config);
        this.registeredAt = Instant.now();
        this.lastUpdatedAt = this.registeredAt;
    }
    
    /**
     * 生成配置版本号
     */
    private String generateConfigVersion(AgentConfig config) {
        // 基于配置内容生成简单的版本标识
        return Integer.toHexString(config.hashCode());
    }
    
    /**
     * 增加引用计数
     */
    public synchronized void acquire() {
        this.refCount++;
    }
    
    /**
     * 减少引用计数
     */
    public synchronized void release() {
        if (this.refCount > 0) {
            this.refCount--;
        }
        // 如果标记为待删除且引用计数为0，可以安全删除
        if (markedForDeletion && this.refCount == 0) {
            notifyAll();
        }
    }
    
    /**
     * 获取当前引用计数
     */
    public int getRefCount() {
        return refCount;
    }
    
    /**
     * 标记为待删除
     */
    public void markForDeletion() {
        this.markedForDeletion = true;
    }
    
    /**
     * 是否可以安全删除
     */
    public boolean canSafelyDelete() {
        return markedForDeletion && refCount == 0;
    }
    
    /**
     * 等待引用计数归零
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否成功（true=引用归零，false=超时）
     */
    public synchronized boolean waitForRelease(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (refCount > 0 && System.currentTimeMillis() < deadline) {
            try {
                wait(deadline - System.currentTimeMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return refCount == 0;
    }
    
    /**
     * 更新时间戳
     */
    public void touch() {
        this.lastUpdatedAt = Instant.now();
    }
    
    /**
     * 检查配置是否与当前条目相同
     */
    public boolean isSameConfig(AgentConfig newConfig) {
        return this.configVersion.equals(generateConfigVersion(newConfig));
    }
}