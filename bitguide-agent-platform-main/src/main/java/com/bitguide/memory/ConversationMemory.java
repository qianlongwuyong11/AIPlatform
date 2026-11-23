package com.bitguide.memory;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话记忆管理器
 * 支持跨轮次的上下文保持和任务跟踪
 */
@Slf4j
public class ConversationMemory {
    
    /**
     * 会话存储
     */
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    
    /**
     * 最大历史记录数
     */
    private final int maxHistoryLength;
    
    /**
     * 会话超时时间（毫秒）
     */
    private final long sessionTimeoutMs;
    
    /**
     * 压缩阈值
     */
    private final int compressionThreshold;
    
    public ConversationMemory(int maxHistoryLength, long sessionTimeoutMs, int compressionThreshold) {
        this.maxHistoryLength = maxHistoryLength;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.compressionThreshold = compressionThreshold;
    }
    
    /**
     * 使用默认配置创建
     */
    public ConversationMemory() {
        this(50, 3600000, 30); // 默认: 50条历史, 1小时超时, 30条压缩阈值
    }
    
    /**
     * 获取或创建会话上下文
     */
    public SessionContext getOrCreateContext(String sessionId) {
        return sessions.computeIfAbsent(sessionId, this::createNewContext);
    }
    
    /**
     * 获取会话上下文
     */
    public SessionContext getContext(String sessionId) {
        return sessions.get(sessionId);
    }
    
    /**
     * 创建新会话上下文
     */
    private SessionContext createNewContext(String sessionId) {
        log.info("创建新会话上下文: {}", sessionId);
        return new SessionContext(sessionId);
    }
    
    /**
     * 记录用户消息
     */
    public void recordUserMessage(String sessionId, String message) {
        SessionContext context = getOrCreateContext(sessionId);
        context.addInteraction(new Interaction(InteractionType.USER, message));
        
        // 检查是否需要压缩
        checkAndCompress(context);
    }
    
    /**
     * 记录助手响应
     */
    public void recordAssistantResponse(String sessionId, String response) {
        SessionContext context = getOrCreateContext(sessionId);
        context.addInteraction(new Interaction(InteractionType.ASSISTANT, response));
        
        // 检查是否需要压缩
        checkAndCompress(context);
    }
    
    /**
     * 记录工具调用
     */
    public void recordToolCall(String sessionId, String toolName, Map<String, Object> params, Object result) {
        SessionContext context = getOrCreateContext(sessionId);
        ToolCallRecord record = new ToolCallRecord(toolName, params, result);
        context.addToolCall(record);
    }
    
    
    /**
     * 设置会话变量
     */
    public void setVariable(String sessionId, String key, Object value) {
        SessionContext context = getOrCreateContext(sessionId);
        context.setVariable(key, value);
    }
    
    /**
     * 获取会话变量
     */
    public Object getVariable(String sessionId, String key) {
        SessionContext context = getContext(sessionId);
        return context != null ? context.getVariable(key) : null;
    }
    
    /**
     * 获取所有会话变量
     */
    public Map<String, Object> getVariables(String sessionId) {
        SessionContext context = getContext(sessionId);
        return context != null ? context.getVariables() : Collections.emptyMap();
    }
    
    /**
     * 获取交互历史
     */
    public List<Interaction> getHistory(String sessionId) {
        SessionContext context = getContext(sessionId);
        return context != null ? context.getInteractions() : Collections.emptyList();
    }
    
    /**
    
    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("会话已清除: {}", sessionId);
    }

    /**
     * 按前缀清除会话（清除该 session 下所有 Agent 的历史）
     * 当使用 sessionId:agentId 作为 key 时，传入 sessionId 即可清除所有关联的 Agent 会话
     */
    public void clearSessionByPrefix(String prefix) {
        int[] count = {0};
        sessions.keySet().removeIf(key -> {
            if (key.equals(prefix) || key.startsWith(prefix + ":")) {
                count[0]++;
                return true;
            }
            return false;
        });
        log.info("按前缀清除会话: prefix={}, 清除数量={}", prefix, count[0]);
    }
    
    /**
     * 清除所有过期会话
     */
    public void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionContext context = entry.getValue();
            return (now - context.getLastActivityTime()) > sessionTimeoutMs;
        });
    }

    /**
     * 清除所有会话（Agent 关闭时调用）
     */
    public void clearAll() {
        sessions.clear();
        log.info("所有会话已清除");
    }
    
    /**
     * 检查并压缩历史
     */
    private void checkAndCompress(SessionContext context) {
        if (context.getInteractions().size() > compressionThreshold) {
            compressHistory(context);
        }
    }
    
    /**
     * 压缩历史记录
     */
    private void compressHistory(SessionContext context) {
        List<Interaction> interactions = context.getInteractions();
        
        if (interactions.size() <= maxHistoryLength) {
            return;
        }
        
        // 保留最近的交互记录
        int toCompress = interactions.size() - maxHistoryLength / 2;
        List<Interaction> toKeep = interactions.subList(toCompress, interactions.size());
        
        // 创建摘要
        String summary = createSummary(interactions.subList(0, toCompress));
        
        // 更新交互列表
        context.setInteractions(new ArrayList<>());
        if (summary != null && !summary.isEmpty()) {
            context.addInteraction(new Interaction(InteractionType.SUMMARY, summary));
        }
        context.getInteractions().addAll(toKeep);
        
        log.info("历史记录已压缩: 会话={}, 原记录数={}, 压缩后={}", 
                context.getSessionId(), interactions.size(), context.getInteractions().size());
    }
    
    /**
     * 创建历史摘要
     */
    private String createSummary(List<Interaction> interactions) {
        StringBuilder sb = new StringBuilder();
        sb.append("[历史摘要]\n");
        
        int userCount = 0;
        int assistantCount = 0;
        StringBuilder topics = new StringBuilder();
        
        for (Interaction interaction : interactions) {
            if (interaction.getType() == InteractionType.USER) {
                userCount++;
                // 提取关键主题
                String content = interaction.getContent();
                if (content.length() > 50) {
                    content = content.substring(0, 50) + "...";
                }
                topics.append("- ").append(content).append("\n");
            } else if (interaction.getType() == InteractionType.ASSISTANT) {
                assistantCount++;
            }
        }
        
        sb.append("用户消息数: ").append(userCount).append("\n");
        sb.append("助手响应数: ").append(assistantCount).append("\n");
        sb.append("主要话题:\n").append(topics);
        
        return sb.toString();
    }
    
    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}