package com.bitguide.agent;

import com.bitguide.log.ConsoleLog;
import com.bitguide.memory.ConversationMemory;
import com.bitguide.rag.RagService;
import com.bitguide.skill.SkillManager;
import com.bitguide.tool.Tool;
import com.bitguide.tool.ToolManager;
import com.bitguide.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一的 Agent 实现
 * 支持多轮对话、工具调用、RAG 知识检索、Skill 增强
 * 
 * 核心模式：ReAct（Think → Act → Observe 循环）
 * 每步由 LLM 动态决策，支持多轮工具调用
 */
@Slf4j
public class BaseAgent implements Agent, AutoCloseable {
    
    private final String id;
    private final String name;
    private final ChatLanguageModel model;
    private final String systemPrompt;
    private final ToolManager toolManager;
    private final List<ToolSpecification> toolSpecifications;
    private final String knowledgeBase;
    private final RagService ragService;
    
    // 对话记忆（支持多轮对话）
    private final ConversationMemory memory;
    
    // 可选的技能管理器（用于 Skill 自动路由）
    private SkillManager skillManager;
    
    // 默认会话ID
    private static final String DEFAULT_SESSION = "default";
    
    private BaseAgent(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.toolManager = builder.toolManager != null ? builder.toolManager : new ToolManager();
        this.toolSpecifications = new ArrayList<>();
        this.knowledgeBase = builder.knowledgeBase;
        this.ragService = builder.ragService;
        this.memory = builder.memory;
        this.skillManager = builder.skillManager;
        
        // 注册工具到 LangChain4j
        registerTools();
        
        log.info("Agent 创建完成: id={}, name={}, tools={}",
                id, name, toolManager.getToolNames());
        ConsoleLog.info("[Agent]", "Agent 创建完成: id=%s, name=%s, tools=%s",
                id, name, toolManager.getToolNames());
    }
    
    /**
     * 注册工具到 LangChain4j
     */
    private void registerTools() {
        for (Tool tool : toolManager.getAllTools()) {
            ToolSpecification spec = buildToolSpec(tool);
            toolSpecifications.add(spec);
            log.debug("注册工具规格: {} (参数定义: {})", tool.getName(), 
                    tool.getParametersSchema() != null ? "有" : "无");
        }
    }
    
    /**
     * 构建包含参数定义的 ToolSpecification
     */
    private ToolSpecification buildToolSpec(Tool tool) {
        String description = tool.getDescription();
        
        // 将参数 Schema 附加到描述中，让 LLM 知道工具需要什么参数
        String paramSchema = tool.getParametersSchema();
        if (paramSchema != null && !paramSchema.isBlank()) {
            description += "\n\nParameters (JSON Schema): " + paramSchema;
        }
        
        return ToolSpecification.builder()
                .name(tool.getName())
                .description(description)
                .build();
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String chat(String userMessage) {
        return chat(userMessage, DEFAULT_SESSION);
    }
    
    @Override
    public String chat(String userMessage, String sessionId) {
        return chat(userMessage, sessionId, null);
    }
    
    @Override
    public String chat(String userMessage, String sessionId, String userId) {
        log.debug("收到用户消息 [session={}, user={}]: {}", sessionId, userId, userMessage);
        
        // 生成完整的会话标识（支持用户隔离）
        String fullSessionId = buildFullSessionId(sessionId, userId);
        
        // ★ Skill 自动路由：先尝试匹配 Skill，匹配成功则走 Skill 增强模式
        if (skillManager != null) {
            try {
                SkillManager.MatchResult matchResult = skillManager.matchAndExecute(this, userMessage, fullSessionId);
                if (matchResult.isMatched()) {
                    ConsoleLog.info("[Skill]", "技能匹配成功，返回 Skill 增强响应");
                    if (matchResult.isSuccess()) {
                        return matchResult.getResponse();
                    } else {
                        return "技能执行失败: " + matchResult.getError();
                    }
                }
                // 未匹配到技能，继续走普通对话流程
            } catch (Exception e) {
                ConsoleLog.warn("[Skill]", "技能匹配异常，降级为普通对话: %s", e.getMessage());
            }
        }
        
        // 记录用户消息
        memory.recordUserMessage(fullSessionId, userMessage);
        
        // ReAct 即时响应模式：RAG → LLM → 工具循环
        String response = chatDirectly(userMessage, fullSessionId);
        
        // 记录助手响应
        memory.recordAssistantResponse(fullSessionId, response);
        
        log.debug("Agent 响应 [session={}]: {}", sessionId, response);
        return response;
    }
    
    @Override
    public String chatWithSkill(String userMessage, String sessionId, String skillPrompt) {
        log.debug("[Skill] 收到技能增强对话 [session={}]: {}", sessionId, userMessage);
        
        String fullSessionId = buildFullSessionId(sessionId, null);
        
        // 记录用户消息
        memory.recordUserMessage(fullSessionId, userMessage);
        
        // 使用 Skill Prompt 替换默认 SystemPrompt 执行 ReAct 对话
        String response = chatDirectlyWithPrompt(userMessage, fullSessionId, skillPrompt);
        
        // 记录助手响应
        memory.recordAssistantResponse(fullSessionId, response);
        
        log.debug("[Skill] Agent 响应 [session={}]: {}", sessionId, response);
        return response;
    }
    
    @Override
    public void registerTool(Tool tool) {
        if (tool == null) {
            log.warn("[Skill] 尝试注册空工具，已忽略");
            return;
        }
        
        // 如果工具已存在，跳过
        if (toolManager.hasTool(tool.getName())) {
            log.debug("[Skill] 工具已存在，跳过注册: {}", tool.getName());
            return;
        }
        
        // 注册到 ToolManager
        toolManager.registerTool(tool);
        
        // 同步注册到 LangChain4j ToolSpecifications（含参数 Schema）
        ToolSpecification spec = buildToolSpec(tool);
        toolSpecifications.add(spec);
        
        ConsoleLog.info("[Skill]", "动态注册工具: %s - %s (参数定义: %s)",
                tool.getName(), tool.getDescription(),
                tool.getParametersSchema() != null ? "有" : "无");
    }
    
    @Override
    public boolean hasTool(String name) {
        return toolManager.hasTool(name);
    }
    
    @Override
    public void clearSession(String sessionId) {
        clearSession(sessionId, null);
    }
    
    /**
     * 清除会话（支持用户隔离）
     */
    public void clearSession(String sessionId, String userId) {
        String fullSessionId = buildFullSessionId(sessionId, userId);
        memory.clearSession(fullSessionId);
        log.info("会话已清除: {}", fullSessionId);
    }
    
    /**
     * 构建完整的会话ID（支持用户隔离）
     */
    private String buildFullSessionId(String sessionId, String userId) {
        if (userId != null && !userId.isEmpty()) {
            return userId + ":" + sessionId;
        }
        return sessionId;
    }
    
    /**
     * 即时响应模式：RAG → LLM → 工具循环（ReAct）
     */
    private String chatDirectly(String userMessage, String sessionId) {
        // 1. RAG 增强（如果配置了知识库）
        String enhancedMessage = enhanceWithRag(userMessage);
        
        // 2. 构建对话历史
        List<ChatMessage> chatHistory = buildChatHistory(sessionId, enhancedMessage);
        
        // 3. LLM 调用 + 工具循环
        return chatWithTools(chatHistory, sessionId);
    }
    
    /**
     * 使用自定义 SystemPrompt 的即时响应模式（供 Skill 增强使用）
     */
    private String chatDirectlyWithPrompt(String userMessage, String sessionId, String skillPrompt) {
        // 1. RAG 增强（如果配置了知识库）
        String enhancedMessage = enhanceWithRag(userMessage);
        
        // 2. 使用 skillPrompt 替代默认 systemPrompt 构建对话历史
        List<ChatMessage> chatHistory = buildChatHistoryWithPrompt(sessionId, enhancedMessage, skillPrompt);
        
        // 3. LLM 调用 + 工具循环
        return chatWithTools(chatHistory, sessionId);
    }
    
    /**
     * RAG 增强：检索知识库并构建增强消息
     */
    private String enhanceWithRag(String userMessage) {
        if (knowledgeBase == null || knowledgeBase.isEmpty() || ragService == null) {
            return userMessage;
        }
        
        try {
            String ragContext = ragService.buildContext(knowledgeBase, userMessage);
            if (ragContext != null && !ragContext.isEmpty()) {
                ConsoleLog.info("[RAG]", "检索到相关上下文，长度: %d", ragContext.length());
                return buildRagEnhancedMessage(userMessage, ragContext);
            }
        } catch (Exception e) {
            ConsoleLog.warn("[RAG]", "检索失败，继续使用原始消息: %s", e.getMessage());
        }
        
        return userMessage;
    }
    
    /**
     * 构建 RAG 增强消息
     */
    private String buildRagEnhancedMessage(String userMessage, String context) {
        return String.format(
                "[参考上下文]\n%s\n\n[用户问题]\n%s",
                context,
                userMessage
        );
    }
    
    /**
     * 构建对话历史
     */
    private List<ChatMessage> buildChatHistory(String sessionId, String currentUserMessage) {
        List<ChatMessage> history = new ArrayList<>();
        
        // 添加系统提示词
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            history.add(new SystemMessage(systemPrompt));
        }
        
        // 从 Memory 获取历史对话（如果有）
        var interactions = memory.getHistory(sessionId);
        if (interactions != null && !interactions.isEmpty()) {
            for (var interaction : interactions) {
                switch (interaction.getType()) {
                    case USER:
                        history.add(new UserMessage(interaction.getContent()));
                        break;
                    case ASSISTANT:
                        history.add(new AiMessage(interaction.getContent()));
                        break;
                    case SYSTEM:
                        history.add(new SystemMessage(interaction.getContent()));
                        break;
                    default:
                        break;
                }
            }
        }
        
        // 添加当前用户消息
        history.add(new UserMessage(currentUserMessage));
        
        return history;
    }
    
    /**
     * 构建对话历史（使用自定义 SystemPrompt，供 Skill 增强使用）
     */
    private List<ChatMessage> buildChatHistoryWithPrompt(String sessionId, String currentUserMessage, String customPrompt) {
        List<ChatMessage> history = new ArrayList<>();
        
        // 使用 Skill Prompt 替代默认 systemPrompt
        if (customPrompt != null && !customPrompt.isEmpty()) {
            history.add(new SystemMessage(customPrompt));
        }
        
        // 从 Memory 获取历史对话（如果有）
        var interactions = memory.getHistory(sessionId);
        if (interactions != null && !interactions.isEmpty()) {
            for (var interaction : interactions) {
                switch (interaction.getType()) {
                    case USER:
                        history.add(new UserMessage(interaction.getContent()));
                        break;
                    case ASSISTANT:
                        history.add(new AiMessage(interaction.getContent()));
                        break;
                    case SYSTEM:
                        history.add(new SystemMessage(interaction.getContent()));
                        break;
                    default:
                        break;
                }
            }
        }
        
        // 添加当前用户消息
        history.add(new UserMessage(currentUserMessage));
        
        return history;
    }
    
    /**
     * 带工具调用的对话（ReAct 核心：Think → Act → Observe 循环）
     */
    private String chatWithTools(List<ChatMessage> chatHistory, String sessionId) {
        int maxIterations = 10;
        int iteration = 0;
        
        // 用于当前对话的消息列表（会在工具调用中增长）
        List<ChatMessage> currentHistory = new ArrayList<>(chatHistory);
        
        while (iteration < maxIterations) {
            iteration++;
            
            // 构建请求
            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(currentHistory);
            
            // 添加工具规格
            if (!toolSpecifications.isEmpty()) {
                requestBuilder.toolSpecifications(toolSpecifications);
            }
            
            ChatRequest request = requestBuilder.build();
            ChatResponse response = model.chat(request);
            
            AiMessage aiMessage = response.aiMessage();
            
            // 检查是否有工具调用请求
            if (aiMessage.hasToolExecutionRequests()) {
                ConsoleLog.info("[Agent]", "LLM 请求工具调用，迭代次数: %d", iteration);
                
                // 添加 AI 消息到历史（包含工具调用请求）
                currentHistory.add(aiMessage);
                
                // 执行所有工具调用
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    executeToolCall(toolRequest, currentHistory, sessionId);
                }
                
                // 继续循环，让 LLM 处理工具结果
                continue;
            }
            
            // 没有工具调用，返回最终响应
            String responseText = aiMessage.text();
            return responseText;
        }
        
        // 达到最大迭代次数
        return "抱歉，处理您的请求时工具调用次数超过限制。";
    }
    
    /**
     * 执行工具调用
     */
    private void executeToolCall(ToolExecutionRequest toolRequest, 
                                  List<ChatMessage> chatHistory, 
                                  String sessionId) {
        String toolName = toolRequest.name();
        String argumentsJson = toolRequest.arguments();
        
        ConsoleLog.info("[Tool]", "执行工具: %s, 参数: %s", toolName, argumentsJson);
        
        try {
            // 解析参数
            Map<String, Object> params = parseArguments(argumentsJson);
            
            // 执行工具
            ToolResult result = toolManager.execute(toolName, params);
            
            // 构建工具响应消息
            String toolResponse = result.getOutput();
            ConsoleLog.toolSuccess(toolName, toolResponse);
            
            // 记录工具调用
            memory.recordToolCall(sessionId, toolName, params, toolResponse);
            
            // 添加工具执行结果消息
            ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(
                    toolRequest,
                    toolResponse
            );
            chatHistory.add(resultMessage);
            
        } catch (Exception e) {
            ConsoleLog.toolError(toolName, e);
            
            // 添加错误结果消息
            ToolExecutionResultMessage errorMessage = ToolExecutionResultMessage.from(
                    toolRequest,
                    "工具执行失败: " + e.getMessage()
            );
            chatHistory.add(errorMessage);
        }
    }
    
    /**
     * 解析工具参数
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return Map.of();
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(argumentsJson, Map.class);
        } catch (Exception e) {
            log.warn("解析工具参数失败: {}", e.getMessage());
            return Map.of();
        }
    }
    
    /**
     * 获取工具管理器
     */
    public ToolManager getToolManager() {
        return toolManager;
    }
    
    /**
     * 获取对话记忆
     */
    public ConversationMemory getMemory() {
        return memory;
    }

    @Override
    public void close() {
        // 清理会话记忆（释放内存）
        if (memory != null) {
            memory.clearAll();
        }
        log.info("Agent [{}] 资源已释放", id);
    }

    /**
     * Builder 模式
     */
    public static class Builder {
        private String id;
        private String name;
        private ChatLanguageModel model;
        private String systemPrompt;
        private ToolManager toolManager;
        private String knowledgeBase;
        private RagService ragService;
        private ConversationMemory memory;
        private SkillManager skillManager;
        
        public Builder id(String id) {
            this.id = id;
            return this;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder model(ChatLanguageModel model) {
            this.model = model;
            return this;
        }
        
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }
        
        public Builder toolManager(ToolManager toolManager) {
            this.toolManager = toolManager;
            return this;
        }
        
        public Builder knowledgeBase(String knowledgeBase) {
            this.knowledgeBase = knowledgeBase;
            return this;
        }
        
        public Builder ragService(RagService ragService) {
            this.ragService = ragService;
            return this;
        }
        
        public Builder memory(ConversationMemory memory) {
            this.memory = memory;
            return this;
        }
        
        public Builder skillManager(SkillManager skillManager) {
            this.skillManager = skillManager;
            return this;
        }
        
        public BaseAgent build() {
            Objects.requireNonNull(id, "Agent id 不能为空");
            Objects.requireNonNull(name, "Agent name 不能为空");
            Objects.requireNonNull(model, "ChatLanguageModel 不能为空");
            Objects.requireNonNull(memory, "ConversationMemory 不能为空");
            
            return new BaseAgent(this);
        }
    }
}