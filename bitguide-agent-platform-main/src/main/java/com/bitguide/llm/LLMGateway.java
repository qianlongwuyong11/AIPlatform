package com.bitguide.llm;

import com.bitguide.config.ModelConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 网关
 * 负责创建和管理 LLM 客户端
 * 
 * 支持两种模式：
 * 1. 普通模式 - 用于一般对话
 * 2. JSON 模式 - 用于结构化输出（AI Services）
 */
@Slf4j
public class LLMGateway {
    
    private final Map<String, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();
    
    /**
     * 获取或创建 LLM 客户端（普通模式）
     */
    public ChatLanguageModel getOrCreateModel(ModelConfig config) {
        return getOrCreateModel(config, false);
    }
    
    /**
     * 获取或创建 LLM 客户端
     * 
     * @param config 模型配置
     * @param jsonMode 是否启用 JSON 模式（用于结构化输出）
     * @return ChatLanguageModel
     */
    public ChatLanguageModel getOrCreateModel(ModelConfig config, boolean jsonMode) {
        String cacheKey = config.getModelName() + (jsonMode ? "_json" : "");
        return modelCache.computeIfAbsent(cacheKey, k -> createModel(config, jsonMode));
    }
    
    /**
     * 创建 LLM 客户端
     */
    private ChatLanguageModel createModel(ModelConfig config, boolean jsonMode) {
        log.info("创建 LLM 客户端: provider={}, model={}, jsonMode={}", 
                config.getProvider(), config.getModelName(), jsonMode);
        
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(Duration.ofSeconds(60));
        
        // AI Services 会自动处理 JSON 输出的解析
        // 如果使用的是 OpenAI 的新模型（如 gpt-4o），可以启用严格模式
        // 但目前 AI Services 已经能够很好地处理 JSON 输出
        
        return builder.build();
    }
    
    /**
     * 清除缓存的模型（用于配置更新时）
     */
    public void clearCache() {
        modelCache.clear();
        log.info("LLM 模型缓存已清除");
    }
}