package com.bitguide.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * 工具调用记录
 * 记录工具的调用参数和结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {
    
    /**
     * 工具名称
     */
    private String toolName;
    
    /**
     * 调用参数
     */
    private Map<String, Object> parameters;
    
    /**
     * 调用结果
     */
    private Object result;
    
    /**
     * 调用时间
     */
    private Instant timestamp;
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
    
    /**
     * 执行时间（毫秒）
     */
    private long executionTimeMs;
    
    /**
     * 简化构造函数
     */
    public ToolCallRecord(String toolName, Map<String, Object> parameters, Object result) {
        this.toolName = toolName;
        this.parameters = parameters;
        this.result = result;
        this.timestamp = Instant.now();
        this.success = true;
    }
    
    /**
     * 创建失败记录
     */
    public static ToolCallRecord failure(String toolName, Map<String, Object> parameters, 
                                          String errorMessage, long executionTimeMs) {
        ToolCallRecord record = new ToolCallRecord();
        record.setToolName(toolName);
        record.setParameters(parameters);
        record.setTimestamp(Instant.now());
        record.setSuccess(false);
        record.setErrorMessage(errorMessage);
        record.setExecutionTimeMs(executionTimeMs);
        return record;
    }
    
    /**
     * 创建成功记录
     */
    public static ToolCallRecord success(String toolName, Map<String, Object> parameters, 
                                          Object result, long executionTimeMs) {
        ToolCallRecord record = new ToolCallRecord();
        record.setToolName(toolName);
        record.setParameters(parameters);
        record.setResult(result);
        record.setTimestamp(Instant.now());
        record.setSuccess(true);
        record.setExecutionTimeMs(executionTimeMs);
        return record;
    }
    
    /**
     * 获取格式化的调用信息
     */
    public String getFormattedCall() {
        return String.format("Tool: %s, Params: %s", toolName, parameters);
    }
    
    /**
     * 获取格式化的结果信息
     */
    public String getFormattedResult() {
        if (success) {
            String resultStr = result != null ? result.toString() : "null";
            if (resultStr.length() > 200) {
                resultStr = resultStr.substring(0, 200) + "...";
            }
            return resultStr;
        } else {
            return "Error: " + errorMessage;
        }
    }
}