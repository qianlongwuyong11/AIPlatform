package com.bitguide.skill;

import com.bitguide.log.ConsoleLog;
import com.bitguide.agent.Agent;
import com.bitguide.tool.Tool;
import com.bitguide.tool.ToolManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 技能增强器
 *
 * 核心职责：
 * 1. 将 Skill 声明的 toolRefs 注入到 Agent
 * 2. 用 Skill 的 systemPrompt 替换 Agent 默认 Prompt
 * 3. 复用 Agent 的 ReACT/Planning 引擎执行
 *
 * 对齐 Go 版本的 Enhancer 设计
 */
@Slf4j
public class SkillEnhancer {
    
    /**
     * 内置工具注册表（Tool 名称 -> Tool 实例工厂）
     */
    private final Map<String, Tool> toolRegistry;
    
    /**
     * 创建增强器
     *
     * @param toolRegistry 内置工具注册表
     */
    public SkillEnhancer(Map<String, Tool> toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    
    public String enhanceAndExecute(Agent agent, Skill skill, String userInput, String sessionId) {
        // 1. 注入 Skill 声明的工具
        injectTools(agent, skill);

        // 2. 构建 Skill Prompt = Skill 的 SystemPrompt + 工具使用指引（用实际工具名）
        String skillPrompt = buildSkillPrompt(skill, agent);

        ConsoleLog.skillExecute(skill.getName(), "");

        // 3. 调用 Agent 的 chatWithSkill（临时替换 SystemPrompt，复用 ReACT/Planning 引擎）
        return agent.chatWithSkill(userInput, sessionId, skillPrompt);
    }
    
    /**
     * 注入 Skill 声明的工具到 Agent
     */
    private void injectTools(Agent agent, Skill skill) {
        if (skill.getToolRefs() == null || skill.getToolRefs().isEmpty()) {
            return;
        }

        for (String toolRef : skill.getToolRefs()) {
            // 1. 精确匹配：内置工具注册表
            if (toolRegistry != null && toolRegistry.containsKey(toolRef)) {
                Tool tool = toolRegistry.get(toolRef);
                agent.registerTool(tool);
                ConsoleLog.debug("[Skill]", "注入内置工具: %s", toolRef);
                continue;
            }

            // 2. 精确匹配：Agent 已注册的工具（MCP 等）
            if (agent.hasTool(toolRef)) {
                ConsoleLog.debug("[Skill]", "工具已存在于 Agent 中: %s", toolRef);
                continue;
            }

            // 3. 模糊匹配：MCP 工具名带 server 前缀（如 legal-tools_statute_of_limitations）
            //    skill 中只写了 statute_of_limitations，需要按后缀匹配
            String matched = findToolBySuffix(agent, toolRef);
            if (matched != null) {
                ConsoleLog.debug("[Skill]", "工具模糊匹配: %s -> %s", toolRef, matched);
                continue;
            }

            // 4. 都没找到
            ConsoleLog.warn("[Skill]", "工具未找到: %s (skill: %s)", toolRef, skill.getName());
        }
    }

    /**
     * 按后缀查找工具（处理 MCP 工具带 server 前缀的情况）
     * 例如 toolRef="statute_of_limitations" 匹配 "legal-tools_statute_of_limitations"
     */
    private String findToolBySuffix(Agent agent, String toolRef) {
        String suffix = "_" + toolRef;
        // agent.hasTool 是精确匹配，这里需要遍历所有工具
        // 利用 BaseAgent 的 getToolManager 获取工具列表
        if (agent instanceof com.bitguide.agent.BaseAgent baseAgent) {
            for (String name : baseAgent.getToolManager().getToolNames()) {
                if (name.equals(toolRef) || name.endsWith(suffix)) {
                    return name;
                }
            }
        }
        return null;
    }
    
    /**
     * 构建技能的 System Prompt
     * 在 Skill 的 systemPrompt 基础上，追加工具使用指引
     * 使用 Agent 中实际注册的工具名（解决 MCP 工具带 server 前缀的问题）
     */
    private String buildSkillPrompt(Skill skill, Agent agent) {
        String prompt = skill.getSystemPrompt();

        if (skill.getToolRefs() != null && !skill.getToolRefs().isEmpty()) {
            prompt += "\n\n## 可用工具\n你可以使用以下工具来完成任务：\n";
            for (String toolRef : skill.getToolRefs()) {
                // 解析实际工具名（处理 MCP 前缀）
                String actualName = resolveToolName(agent, toolRef);
                prompt += "- " + actualName + "\n";
            }
            prompt += "\n请根据需要调用工具，获取信息后给出完整的回答。";
        }

        return prompt;
    }

    /**
     * 解析工具的实际名称
     * 优先精确匹配，否则按后缀匹配 MCP 前缀
     */
    private String resolveToolName(Agent agent, String toolRef) {
        if (agent.hasTool(toolRef)) {
            return toolRef;
        }
        if (agent instanceof com.bitguide.agent.BaseAgent baseAgent) {
            String suffix = "_" + toolRef;
            for (String name : baseAgent.getToolManager().getToolNames()) {
                if (name.endsWith(suffix)) {
                    return name;
                }
            }
        }
        return toolRef;
    }
}