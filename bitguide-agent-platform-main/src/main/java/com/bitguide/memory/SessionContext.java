package com.bitguide.memory;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话上下文
 * 存储单个会话的所有状态信息
 */
@Data
public class SessionContext {
    
    /**
     * 会话ID
     */
    private final String sessionId;
    
    /**
     * 会话创建时间
     */
    private final Instant createdAt;
    
    /**
     * 最后活动时间
     */
    private long lastActivityTime;
    
    /**
     * 交互历史
     */
    private List<Interaction> interactions = new ArrayList<>();
    
    /**
     * 工具调用记录
     */
    private List<ToolCallRecord> toolCalls = new ArrayList<>();
    
    /**
     * 会话变量
     */
    private Map<String, Object> variables = new HashMap<>();
    
    /**
     * 当前执行计划
     */
    
    /**
     * 用户意图
     */
    private String currentIntent;
    
    /**
     * 会话元数据
     */
    private Map<String, Object> metadata = new HashMap<>();
    
    public SessionContext(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 添加交互记录
     */
    public void addInteraction(Interaction interaction) {
        this.interactions.add(interaction);
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 添加工具调用记录
     */
    public void addToolCall(ToolCallRecord toolCall) {
        this.toolCalls.add(toolCall);
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 设置变量
     */
    public void setVariable(String key, Object value) {
        this.variables.put(key, value);
        this.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * 获取变量
     */
    public Object getVariable(String key) {
        return this.variables.get(key);
    }
    
    /**
     * 获取最近的N条交互记录
     */
    public List<Interaction> getRecentInteractions(int n) {
        if (interactions.size() <= n) {
            return new ArrayList<>(interactions);
        }
        return new ArrayList<>(interactions.subList(interactions.size() - n, interactions.size()));
    }
    
    /**
     * 获取用户消息历史
     */
    public List<String> getUserMessages() {
        return interactions.stream()
                .filter(i -> i.getType() == InteractionType.USER)
                .map(Interaction::getContent)
                .toList();
    }
    
    /**
     * 获取助手响应历史
     */
    public List<String> getAssistantResponses() {
        return interactions.stream()
                .filter(i -> i.getType() == InteractionType.ASSISTANT)
                .map(Interaction::getContent)
                .toList();
    }
    
    /**
     * 获取交互次数
     */
    public int getInteractionCount() {
        return interactions.size();
    }
    
    /**
     * 获取工具调用次数
     */
    public int getToolCallCount() {
        return toolCalls.size();
    }
    
    /**
     * 清除历史
     */
    public void clearHistory() {
        this.interactions.clear();
        this.toolCalls.clear();
    }
    
    /**
     * 重置会话
     */
    public void reset() {
        clearHistory();
        this.variables.clear();

        this.currentIntent = null;
        this.metadata.clear();
    }
}