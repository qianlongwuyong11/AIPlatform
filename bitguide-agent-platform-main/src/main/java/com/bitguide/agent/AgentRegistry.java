package com.bitguide.agent;

import com.bitguide.config.AgentConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Agent 注册中心
 * 负责管理 Agent 的生命周期：注册、注销、更新、查询
 */
@Slf4j
public class AgentRegistry {
    
    /**
     * Agent 实例缓存
     */
    private final ConcurrentHashMap<String, AgentEntry> agents = new ConcurrentHashMap<>();
    
    /**
     * Agent 工厂（用于创建 Agent 实例）
     */
    private final AgentFactory agentFactory;
    
    /**
     * 优雅停机超时时间（毫秒）
     */
    private long gracefulShutdownTimeoutMs = 30000;
    
    public AgentRegistry(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }
    
    public AgentRegistry(AgentFactory agentFactory, long gracefulShutdownTimeoutMs) {
        this.agentFactory = agentFactory;
        this.gracefulShutdownTimeoutMs = gracefulShutdownTimeoutMs;
    }
    
    /**
     * 注册 Agent
     * 
     * @param agentId Agent ID
     * @param config Agent 配置
     * @return 是否注册成功
     */
    public boolean register(String agentId, AgentConfig config) {
        if (agentId == null || config == null) {
            log.warn("注册 Agent 失败：agentId 或 config 为空");
            return false;
        }
        
        try {
            // 检查是否已存在
            AgentEntry existing = agents.get(agentId);
            if (existing != null) {
                // 检查配置是否相同
                if (existing.isSameConfig(config)) {
                    log.debug("Agent [{}] 配置未变化，跳过注册", agentId);
                    return true;
                }
                // 配置不同，需要更新
                log.info("Agent [{}] 已存在但配置不同，执行更新", agentId);
                return update(agentId, config);
            }
            
            // 创建新的 Agent 实例
            Agent agent = agentFactory.createAgent(config);
            AgentEntry entry = new AgentEntry(agent, config);
            agents.put(agentId, entry);
            
            log.info("Agent [{}] 注册成功，名称: {}", agentId, config.getName());
            return true;
            
        } catch (Exception e) {
            log.error("注册 Agent [{}] 失败", agentId, e);
            return false;
        }
    }
    
    /**
     * 注销 Agent
     * 
     * @param agentId Agent ID
     * @return 是否注销成功
     */
    public boolean unregister(String agentId) {
        return unregister(agentId, true);
    }
    
    /**
     * 注销 Agent
     * 
     * @param agentId Agent ID
     * @param graceful 是否优雅停机
     * @return 是否注销成功
     */
    public boolean unregister(String agentId, boolean graceful) {
        if (agentId == null) {
            return false;
        }
        
        AgentEntry entry = agents.remove(agentId);
        if (entry == null) {
            log.warn("Agent [{}] 不存在，无需注销", agentId);
            return true;
        }
        
        try {
            if (graceful) {
                // 优雅停机
                gracefulShutdown(entry);
            } else {
                // 立即停机
                shutdownAgent(entry);
            }
            
            log.info("Agent [{}] 注销成功", agentId);
            return true;
            
        } catch (Exception e) {
            log.error("注销 Agent [{}] 失败", agentId, e);
            return false;
        }
    }
    
    /**
     * 更新 Agent
     * 
     * @param agentId Agent ID
     * @param newConfig 新配置
     * @return 是否更新成功
     */
    public boolean update(String agentId, AgentConfig newConfig) {
        if (agentId == null || newConfig == null) {
            return false;
        }
        
        AgentEntry oldEntry = agents.get(agentId);
        if (oldEntry == null) {
            // 不存在，执行注册
            log.info("Agent [{}] 不存在，执行注册", agentId);
            return register(agentId, newConfig);
        }
        
        // 检查配置是否相同
        if (oldEntry.isSameConfig(newConfig)) {
            log.debug("Agent [{}] 配置未变化，跳过更新", agentId);
            return true;
        }
        
        try {
            // 1. 标记旧 Agent 为待删除
            oldEntry.markForDeletion();
            
            // 2. 等待正在处理的请求完成
            boolean released = oldEntry.waitForRelease(gracefulShutdownTimeoutMs);
            if (!released) {
                log.warn("Agent [{}] 等待请求完成超时，强制更新", agentId);
            }
            
            // 3. 创建新的 Agent
            Agent newAgent = agentFactory.createAgent(newConfig);
            AgentEntry newEntry = new AgentEntry(newAgent, newConfig);
            
            // 4. 替换（原子操作）
            agents.put(agentId, newEntry);
            
            // 5. 关闭旧 Agent
            shutdownAgent(oldEntry);
            
            log.info("Agent [{}] 更新成功", agentId);
            return true;
            
        } catch (Exception e) {
            log.error("更新 Agent [{}] 失败", agentId, e);
            return false;
        }
    }
    
    /**
     * 获取 Agent
     * 
     * @param agentId Agent ID
     * @return Agent 实例，如果不存在返回 null
     */
    public Agent get(String agentId) {
        AgentEntry entry = agents.get(agentId);
        if (entry == null) {
            return null;
        }
        
        // 检查是否标记为待删除
        if (entry.isMarkedForDeletion()) {
            return null;
        }
        
        return entry.getAgent();
    }
    
    /**
     * 获取 Agent（增加引用计数）
     * 用于确保在处理请求期间 Agent 不会被删除
     * 
     * @param agentId Agent ID
     * @return AgentEntry，使用后必须调用 release()
     */
    public AgentEntry acquire(String agentId) {
        AgentEntry entry = agents.get(agentId);
        if (entry == null || entry.isMarkedForDeletion()) {
            return null;
        }
        
        entry.acquire();
        return entry;
    }
    
    /**
     * 释放 Agent 引用
     * 
     * @param agentId Agent ID
     */
    public void release(String agentId) {
        AgentEntry entry = agents.get(agentId);
        if (entry != null) {
            entry.release();
        }
    }
    
    /**
     * 检查 Agent 是否存在
     */
    public boolean exists(String agentId) {
        AgentEntry entry = agents.get(agentId);
        return entry != null && !entry.isMarkedForDeletion();
    }
    
    /**
     * 获取所有可用的 Agent ID
     */
    public Set<String> getAvailableAgentIds() {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, AgentEntry> entry : agents.entrySet()) {
            if (!entry.getValue().isMarkedForDeletion()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }
    
    /**
     * 获取所有 Agent 的信息
     */
    public Map<String, AgentInfo> getAllAgentInfo() {
        Map<String, AgentInfo> result = new HashMap<>();
        for (Map.Entry<String, AgentEntry> entry : agents.entrySet()) {
            AgentEntry agentEntry = entry.getValue();
            if (!agentEntry.isMarkedForDeletion()) {
                AgentInfo info = new AgentInfo(
                        agentEntry.getAgent().getId(),
                        agentEntry.getAgent().getName(),
                        agentEntry.getConfig().getDescription(),
                        agentEntry.getRefCount(),
                        agentEntry.getRegisteredAt(),
                        agentEntry.getLastUpdatedAt()
                );
                result.put(entry.getKey(), info);
            }
        }
        return result;
    }
    
    /**
     * 清空所有 Agent
     */
    public void clear() {
        log.info("开始清空所有 Agent，数量: {}", agents.size());
        
        // 标记所有 Agent 为待删除
        for (AgentEntry entry : agents.values()) {
            entry.markForDeletion();
        }
        
        // 等待所有请求完成
        for (AgentEntry entry : agents.values()) {
            try {
                if (!entry.waitForRelease(gracefulShutdownTimeoutMs)) {
                    log.warn("Agent [{}] 等待请求完成超时", entry.getAgent().getId());
                }
            } catch (Exception e) {
                log.warn("等待 Agent [{}] 请求完成异常", entry.getAgent().getId(), e);
            }
        }
        
        // 关闭所有 Agent
        for (AgentEntry entry : agents.values()) {
            try {
                shutdownAgent(entry);
            } catch (Exception e) {
                log.warn("关闭 Agent [{}] 失败", entry.getAgent().getId(), e);
            }
        }
        
        agents.clear();
        log.info("所有 Agent 已清空");
    }
    
    /**
     * 优雅停机
     */
    private void gracefulShutdown(AgentEntry entry) {
        // 等待请求完成
        boolean released = entry.waitForRelease(gracefulShutdownTimeoutMs);
        if (!released) {
            log.warn("Agent [{}] 等待请求完成超时", entry.getAgent().getId());
        }
        
        shutdownAgent(entry);
    }
    
    /**
     * 关闭 Agent
     */
    private void shutdownAgent(AgentEntry entry) {
        Agent agent = entry.getAgent();
        // 1. 关闭 Agent 自身资源（会话记忆等）
        if (agent instanceof AutoCloseable) {
            try {
                ((AutoCloseable) agent).close();
            } catch (Exception e) {
                log.warn("关闭 Agent [{}] 时发生异常", agent.getId(), e);
            }
        }
        // 2. 关闭 Agent 关联的内联 MCP 连接
        if (agentFactory != null) {
            agentFactory.destroyAgent(agent.getId());
        }
    }
    
    /**
     * Agent 信息
     */
    public record AgentInfo(
            String id,
            String name,
            String description,
            int activeRequests,
            java.time.Instant registeredAt,
            java.time.Instant lastUpdatedAt
    ) {}
}