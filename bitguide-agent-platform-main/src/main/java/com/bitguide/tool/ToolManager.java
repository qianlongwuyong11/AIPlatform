package com.bitguide.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 工具管理器
 * 负责工具的注册、查找和执行
 */
@Slf4j
public class ToolManager {
    
    /**
     * 工具注册表
     */
    private final Map<String, Tool> tools = new HashMap<>();
    
    /**
     * 注册工具
     */
    public void registerTool(Tool tool) {
        if (tool == null) {
            log.warn("尝试注册空工具，已忽略");
            return;
        }
        
        String name = tool.getName();
        if (name == null || name.trim().isEmpty()) {
            log.warn("工具名称为空，已忽略注册");
            return;
        }
        
        tools.put(name, tool);
        log.info("工具已注册: {} - {}", name, tool.getDescription());
    }
    
    /**
     * 批量注册工具
     */
    public void registerTools(List<Tool> toolList) {
        if (toolList == null) {
            return;
        }
        toolList.forEach(this::registerTool);
    }
    
    /**
     * 获取工具
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }
    
    /**
     * 获取所有已注册的工具
     */
    public Collection<Tool> getAllTools() {
        return Collections.unmodifiableCollection(tools.values());
    }
    
    /**
     * 获取所有工具名称
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }
    
    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
    
    /**
     * 执行工具
     */
    public ToolResult execute(String toolName, Map<String, Object> params) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            log.warn("工具不存在: {}", toolName);
            return ToolResult.failure("工具不存在: " + toolName);
        }
        
        log.info("执行工具: {} 参数: {}", toolName, params);
        try {
            ToolResult result = tool.execute(params);
            log.info("工具执行完成: {} 结果: {}", toolName, result.isSuccess() ? "成功" : "失败");
            return result;
        } catch (Exception e) {
            log.error("工具执行异常: {} - {}", toolName, e.getMessage(), e);
            return ToolResult.failure("工具执行异常: " + e.getMessage());
        }
    }
    
    /**
     * 注销工具
     */
    public void unregisterTool(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            log.info("工具已注销: {}", name);
        }
    }
    
    /**
     * 清空所有工具
     */
    public void clear() {
        tools.clear();
        log.info("所有工具已清空");
    }
    
    /**
     * 获取工具数量
     */
    public int size() {
        return tools.size();
    }
    
    /**
     * 获取工具注册表（用于 SkillEnhancer 等组件）
     */
    public Map<String, Tool> getToolMap() {
        return Collections.unmodifiableMap(tools);
    }
    
    /**
     * 获取工具描述列表（用于 LLM 理解可用工具）
     */
    public String getToolDescriptions() {
        if (tools.isEmpty()) {
            return "当前没有可用工具";
        }
        
        StringBuilder sb = new StringBuilder("可用工具列表：\n");
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.getName())
              .append(": ").append(tool.getDescription())
              .append("\n");
        }
        return sb.toString();
    }
}