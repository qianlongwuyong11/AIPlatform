package com.bitguide.agent;

import com.bitguide.tool.Tool;

/**
 * Agent 接口
 * 统一的 Agent 抽象，支持多轮对话、会话管理、技能增强
 *
 * 对齐 Go 版本的 Agent 接口，新增：
 * - chatWithSkill: 使用技能增强的 System Prompt 执行对话
 * - registerTool: 动态注册工具（供 Skill 注入工具使用）
 * - hasTool: 检查工具是否已注册
 */
public interface Agent {
    
    /**
     * 获取 Agent ID
     */
    String getId();
    
    /**
     * 获取 Agent 名称
     */
    String getName();
    
    /**
     * 执行对话（无状态，使用默认会话）
     * @param userMessage 用户消息
     * @return Agent 响应
     */
    String chat(String userMessage);
    
    /**
     * 执行对话（支持多轮会话）
     * @param userMessage 用户消息
     * @param sessionId 会话ID（用于多轮对话上下文保持）
     * @return Agent 响应
     */
    String chat(String userMessage, String sessionId);
    
    /**
     * 执行对话（支持多轮会话和用户隔离）
     * @param userMessage 用户消息
     * @param sessionId 会话ID
     * @param userId 用户ID（用于用户维度隔离）
     * @return Agent 响应
     */
    String chat(String userMessage, String sessionId, String userId);
    
    /**
     * 使用技能增强的 System Prompt 执行对话（Skill 增强模式）
     *
     * 核心思路：临时用 Skill 的专业 Prompt 替换默认 System Prompt，复用 ReAct 引擎
     *
     * @param userMessage 用户消息
     * @param sessionId   会话ID
     * @param skillPrompt 技能的 System Prompt（替换 Agent 默认的 System Prompt）
     * @return Agent 响应
     */
    String chatWithSkill(String userMessage, String sessionId, String skillPrompt);
    
    /**
     * 清除会话
     * @param sessionId 会话ID
     */
    void clearSession(String sessionId);
    
    /**
     * 注册工具（支持动态注册，供 Skill 注入工具使用）
     * @param tool 工具实例
     */
    void registerTool(Tool tool);
    
    /**
     * 检查是否已注册指定工具（包括内置工具和 MCP 工具）
     * @param name 工具名称
     * @return 是否已注册
     */
    boolean hasTool(String name);
}