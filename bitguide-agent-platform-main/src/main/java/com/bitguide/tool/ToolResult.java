package com.bitguide.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 工具执行结果
 */
@Data
@Builder
public class ToolResult {
    
    /**
     * 是否执行成功
     */
    private final boolean success;
    
    /**
     * 执行结果数据
     */
    private final String result;
    
    /**
     * 错误信息（失败时）
     */
    private final String error;
    
    /**
     * 创建成功结果
     */
    public static ToolResult success(String result) {
        return ToolResult.builder()
                .success(true)
                .result(result)
                .build();
    }
    
    /**
     * 创建失败结果
     */
    public static ToolResult failure(String error) {
        return ToolResult.builder()
                .success(false)
                .error(error)
                .build();
    }
    
    /**
     * 获取输出内容（成功返回结果，失败返回错误信息）
     */
    public String getOutput() {
        if (success) {
            return result;
        }
        return "错误: " + error;
    }
}