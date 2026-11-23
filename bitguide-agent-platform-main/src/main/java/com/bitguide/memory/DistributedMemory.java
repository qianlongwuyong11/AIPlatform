package com.bitguide.memory;

import com.bitguide.config.RedisConfig;
import com.bitguide.redis.RedisClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 分布式会话存储
 * 使用 Redis 实现跨实例的会话共享
 * 注意：这是一个新类，不修改原有的 ConversationMemory
 */
@Slf4j
public class DistributedMemory {
    
    private final RedisClient redisClient;
    private final RedisConfig.SessionConfig sessionConfig;
    private final ObjectMapper objectMapper;
    
    /**
     * 本地缓存（用于减少 Redis 访问）
     */
    private final Map<String, SessionContext> localCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    public DistributedMemory(RedisClient redisClient, RedisConfig config) {
        this.redisClient = redisClient;
        this.sessionConfig = config.getSession();
        this.objectMapper = new ObjectMapper();
        log.info("分布式会话存储初始化完成, keyPrefix={}", sessionConfig.getKeyPrefix());
    }
    
    /**
     * 获取会话 Key
     */
    private String getSessionKey(String sessionId) {
        return sessionConfig.getKeyPrefix() + sessionId;
    }
    
    /**
     * 获取或创建会话上下文
     */
    public SessionContext getOrCreateContext(String sessionId) {
        SessionContext context = localCache.get(sessionId);
        if (context != null) {
            return context;
        }
        
        // 从 Redis 加载
        context = loadFromRedis(sessionId);
        if (context == null) {
            context = new SessionContext(sessionId);
        }
        
        localCache.put(sessionId, context);
        return context;
    }
    
    /**
     * 获取会话上下文
     */
    public SessionContext getContext(String sessionId) {
        return getOrCreateContext(sessionId);
    }
    
    /**
     * 从 Redis 加载会话
     */
    private SessionContext loadFromRedis(String sessionId) {
        try {
            String json = redisClient.get(getSessionKey(sessionId));
            if (json != null) {
                return deserializeSession(json);
            }
        } catch (Exception e) {
            log.warn("从 Redis 加载会话失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
        return null;
    }
    
    /**
     * 保存会话到 Redis
     */
    private void saveToRedis(String sessionId, SessionContext context) {
        try {
            String json = serializeSession(context);
            redisClient.setex(getSessionKey(sessionId), json, sessionConfig.getTimeoutMs());
        } catch (Exception e) {
            log.error("保存会话到 Redis 失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }
    
    /**
     * 序列化会话
     */
    private String serializeSession(SessionContext context) throws JsonProcessingException {
        Map<String, Object> data = new HashMap<>();
        data.put("sessionId", context.getSessionId());
        data.put("variables", context.getVariables());
        data.put("lastActivityTime", context.getLastActivityTime());
        
        // 序列化交互记录（简化版，只保留最近N条）
        List<Map<String, Object>> interactions = new ArrayList<>();
        int count = 0;
        for (Interaction interaction : context.getInteractions()) {
            Map<String, Object> interactionData = new HashMap<>();
            interactionData.put("type", interaction.getType().name());
            interactionData.put("content", interaction.getContent());
            interactionData.put("timestamp", interaction.getTimestamp().toEpochMilli());
            interactions.add(interactionData);
            if (++count >= 50) break; // 最多50条
        }
        data.put("interactions", interactions);
        
        return objectMapper.writeValueAsString(data);
    }
    
    /**
     * 反序列化会话
     */
    @SuppressWarnings("unchecked")
    private SessionContext deserializeSession(String json) throws JsonProcessingException {
        Map<String, Object> data = objectMapper.readValue(json, Map.class);
        String sessionId = (String) data.get("sessionId");
        SessionContext context = new SessionContext(sessionId);
        
        // 恢复变量
        Map<String, Object> variables = (Map<String, Object>) data.get("variables");
        if (variables != null) {
            variables.forEach(context::setVariable);
        }
        
        // 恢复交互记录
        List<Map<String, Object>> interactions = (List<Map<String, Object>>) data.get("interactions");
        if (interactions != null) {
            for (Map<String, Object> interactionData : interactions) {
                InteractionType type = InteractionType.valueOf((String) interactionData.get("type"));
                String content = (String) interactionData.get("content");
                Object timestampObj = interactionData.get("timestamp");
                java.time.Instant timestamp;
                if (timestampObj instanceof Number) {
                    timestamp = java.time.Instant.ofEpochMilli(((Number) timestampObj).longValue());
                } else if (timestampObj instanceof String) {
                    timestamp = java.time.Instant.parse((String) timestampObj);
                } else {
                    timestamp = java.time.Instant.now();
                }
                Interaction interaction = new Interaction(type, content);
                interaction.setTimestamp(timestamp);
                context.addInteraction(interaction);
            }
        }
        
        return context;
    }
    
    /**
     * 记录用户消息
     */
    public void recordUserMessage(String sessionId, String message) {
        SessionContext context = getOrCreateContext(sessionId);
        context.addInteraction(new Interaction(InteractionType.USER, message));
        saveToRedis(sessionId, context);
    }
    
    /**
     * 记录助手响应
     */
    public void recordAssistantResponse(String sessionId, String response) {
        SessionContext context = getOrCreateContext(sessionId);
        context.addInteraction(new Interaction(InteractionType.ASSISTANT, response));
        saveToRedis(sessionId, context);
    }
    
    /**
     * 记录工具调用
     */
    public void recordToolCall(String sessionId, String toolName, Map<String, Object> params, Object result) {
        SessionContext context = getOrCreateContext(sessionId);
        ToolCallRecord record = new ToolCallRecord(toolName, params, result);
        context.addToolCall(record);
        saveToRedis(sessionId, context);
    }
    
    /**
     * 设置会话变量
     */
    public void setVariable(String sessionId, String key, Object value) {
        SessionContext context = getOrCreateContext(sessionId);
        context.setVariable(key, value);
        saveToRedis(sessionId, context);
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
     * 清除会话
     */
    public void clearSession(String sessionId) {
        localCache.remove(sessionId);
        redisClient.del(getSessionKey(sessionId));
        log.info("会话已清除: {}", sessionId);
    }
    
    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return localCache.size();
    }
}