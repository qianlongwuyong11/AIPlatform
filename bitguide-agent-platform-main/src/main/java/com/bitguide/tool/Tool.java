package com.bitguide.tool;

import java.util.Map;

/**
 * 工具接口
 * 定义 Agent 可调用的工具
 */
public interface Tool {
    
    /**
     * 获取工具名称
     */
    String getName();
    
    /**
     * 获取工具描述
     * 用于 LLM 理解工具用途
     */
    String getDescription();
    
    /**
     * 获取工具参数定义（JSON Schema 格式）
     * 可选实现，用于复杂的参数定义
     */
    default String getParametersSchema() {
        return null;
    }
    
    /**
     * 执行工具
     * @param params 工具参数
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> params);
}