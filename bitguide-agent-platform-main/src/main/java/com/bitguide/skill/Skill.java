package com.bitguide.skill;

import lombok.*;

import java.util.List;

/**
 * 技能数据模型（能力增强模式）
 *
 * 业界主流做法：Skill = 专业 Prompt + 工具绑定 + 示例
 * 不写死执行步骤，LLM 在 ReACT 循环中自主决策工具调用
 *
 * 参考：
 * - Semantic Kernel: Skill = Semantic Function + Native Function
 * - OpenAI Assistants: Assistant = Instructions + Tools + Files
 * - LangChain: Runnable 链，LLM 决定分支
 *
 * 对齐 Go 版本的 BaseSkill 设计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    
    /**
     * 技能唯一标识（通常为 SKILL.md 所在目录名）
     */
    private String id;
    
    /**
     * 技能名称
     */
    private String name;
    
    /**
     * 技能描述（用于匹配路由）
     */
    private String description;
    
    /**
     * 系统提示词（专业的执行指南）
     * 来自 SKILL.md 的 Markdown 正文部分
     */
    private String systemPrompt;
    
    /**
     * 所需工具引用列表
     * Skill 执行时会被注入到 Agent 中
     */
    private List<String> toolRefs;
    
    /**
     * Few-shot 示例列表
     */
    private List<Example> examples;
    
    /**
     * 技能文件路径（用于动态重载）
     */
    private String filePath;
    
    /**
     * 最后修改时间（用于检测变更）
     */
    private long lastModified;
    
    /**
     * Few-shot 示例
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Example {
        /**
         * 用户输入示例
         */
        private String userInput;
        
        /**
         * 期望的响应示例
         */
        private String response;
    }
}