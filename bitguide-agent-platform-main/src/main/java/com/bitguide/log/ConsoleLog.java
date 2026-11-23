package com.bitguide.log;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 终端彩色日志工具
 *
 * 格式：HH:MM:SS.mmm [LEVEL] [Module] message
 * 不同模块使用不同颜色，方便在控制台中快速识别日志来源。
 *
 * 用法：
 *   ConsoleLog.info("[Skill]", "技能加载完成: %d 个", count);
 *   ConsoleLog.error("[MCP]", "连接失败: %s", serverName);
 */
public class ConsoleLog {

    // ==================== ANSI 颜色常量 ====================
    private static final String RESET  = "\033[0m";
    private static final String GRAY   = "\033[90m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED    = "\033[31m";
    private static final String CYAN   = "\033[36m";
    private static final String PURPLE = "\033[35m";
    private static final String BOLD   = "\033[1m";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ==================== 模块颜色映射 ====================
    private static final Map<String, String> MODULE_COLORS = new LinkedHashMap<>();
    static {
        MODULE_COLORS.put("[Tool]",           CYAN);
        MODULE_COLORS.put("[MCP]",            PURPLE);
        MODULE_COLORS.put("[Skill]",          GREEN);
        MODULE_COLORS.put("[Agent]",          YELLOW);
        MODULE_COLORS.put("[RAG]",            CYAN);
        MODULE_COLORS.put("[LLM]",            GREEN);
        MODULE_COLORS.put("[Gateway]",        PURPLE);
        MODULE_COLORS.put("[AuthFilter]",     YELLOW);
        MODULE_COLORS.put("[SecurityFilter]", RED);
        MODULE_COLORS.put("[RateLimitFilter]",CYAN);
        MODULE_COLORS.put("[HTTP]",           GREEN);
        MODULE_COLORS.put("[Dynamic]",        PURPLE);
        MODULE_COLORS.put("[Memory]",         GREEN);
        MODULE_COLORS.put("[DistGateway]",    PURPLE);
        MODULE_COLORS.put("[Lane]",           CYAN);
    }

    // ==================== 级别颜色映射 ====================
    private enum Level {
        DEBUG(GRAY), INFO(GREEN), WARN(YELLOW), ERROR(RED);
        final String color;
        Level(String color) { this.color = color; }
    }

    // 是否启用颜色（默认启用）
    private static boolean colorsEnabled = true;

    public static void setColorsEnabled(boolean enabled) {
        colorsEnabled = enabled;
    }

    // ==================== 通用日志方法 ====================

    public static void debug(String module, String format, Object... args) {
        printLog(Level.DEBUG, module, format, args);
    }

    public static void info(String module, String format, Object... args) {
        printLog(Level.INFO, module, format, args);
    }

    public static void warn(String module, String format, Object... args) {
        printLog(Level.WARN, module, format, args);
    }

    public static void error(String module, String format, Object... args) {
        printLog(Level.ERROR, module, format, args);
    }

    // ==================== 领域便捷方法 ====================

    // ---- Tool ----
    public static void toolRegister(String name, String desc) {
        info("[Tool]", "注册工具: %s - %s", name, desc);
    }

    public static void toolExecute(String name, Object args) {
        info("[Tool]", "执行工具: %s, 参数: %s", name, formatArgs(args));
    }

    public static void toolSuccess(String name, Object result) {
        info("[Tool]", "工具执行成功: %s → %s", name, truncate(String.valueOf(result), 200));
    }

    public static void toolError(String name, Throwable err) {
        error("[Tool]", "工具执行失败: %s - %s", name, err.getMessage());
    }

    public static void toolNotFound(String name) {
        warn("[Tool]", "工具不存在: %s", name);
    }

    // ---- MCP ----
    public static void mcpConnect(String serverName) {
        info("[MCP]", "连接 MCP Server: %s ...", serverName);
    }

    public static void mcpConnectSuccess(String serverName) {
        info("[MCP]", "MCP Server 已连接: %s", serverName);
    }

    public static void mcpConnectError(String serverName, Throwable err) {
        error("[MCP]", "连接 MCP Server 失败: %s - %s", serverName, err.getMessage());
    }

    public static void mcpToolsList(String serverName, int count) {
        info("[MCP]", "发现 %d 个 MCP 工具 (server: %s)", count, serverName);
    }

    public static void mcpToolCall(String toolName, Object args) {
        info("[MCP]", "调用 MCP 工具: %s, 参数: %s", toolName, formatArgs(args));
    }

    // ---- Skill ----
    public static void skillLoad(String name) {
        info("[Skill]", "技能加载: %s", name);
    }

    public static void skillMatched(String name, String input) {
        info("[Skill]", "用户输入匹配到技能: %s", name);
    }

    public static void skillExecute(String name, String input) {
        info("[Skill]", "增强执行: skill=%s, input=%s", name, truncate(input, 50));
    }

    public static void skillError(String name, Throwable err) {
        error("[Skill]", "技能执行失败: %s - %s", name, err.getMessage());
    }

    // ---- Agent ----
    public static void agentLLMRequest(int msgCount) {
        debug("[Agent]", "LLM 请求: %d 条消息", msgCount);
    }

    public static void agentLLMToolCalls(String... toolNames) {
        info("[Agent]", "LLM 决定调用工具: %s", String.join(", ", toolNames));
    }

    public static void agentToolResult(String toolName, Object result) {
        info("[Agent]", "工具 %s 返回: %s", toolName, truncate(String.valueOf(result), 100));
    }

    public static void agentFinalResponse(String response) {
        debug("[Agent]", "最终响应: %s", truncate(response, 200));
    }

    public static void agentIteration(int current, int max) {
        debug("[Agent]", "迭代 %d/%d", current, max);
    }

    // ---- RAG ----
    public static void ragSearch(String query) {
        info("[RAG]", "搜索知识库: %s", truncate(query, 80));
    }

    public static void ragSearchResult(int count, double threshold) {
        info("[RAG]", "检索到 %d 个相关文档 (threshold=%.2f)", count, threshold);
    }

    public static void ragIndex(String kbId, int chunkCount) {
        info("[RAG]", "知识库 [%s] 索引构建完成: %d 个分块", kbId, chunkCount);
    }

    // ---- Gateway ----
    public static void gatewayRequest(String sessionId, String agentId) {
        info("[Gateway]", "收到请求 session=%s agent=%s", sessionId, agentId);
    }

    // ---- Dynamic Config ----
    public static void dynamicConfigEvent(String type, String configType, String configId) {
        info("[Dynamic]", "配置变更事件: type=%s, configType='%s', configId='%s'", type, configType, configId);
    }

    // ==================== 核心输出方法 ====================

    private static void printLog(Level level, String module, String format, Object... args) {
        String timestamp = LocalTime.now().format(TIME_FMT);
        String message = String.format(format, args);

        if (colorsEnabled) {
            String levelStr = String.format("%s%-5s%s", level.color, level.name(), RESET);
            String moduleColor = MODULE_COLORS.getOrDefault(module, CYAN);
            String moduleStr = String.format("%s%s%s", moduleColor, module, RESET);
            String line = String.format("%s %s %s %s", timestamp, levelStr, moduleStr, message);

            if (level == Level.ERROR) {
                System.err.println(line);
            } else {
                System.out.println(line);
            }
        } else {
            String line = String.format("%s [%-5s] %s %s", timestamp, level.name(), module, message);
            if (level == Level.ERROR) {
                System.err.println(line);
            } else {
                System.out.println(line);
            }
        }
    }

    // ==================== 工具方法 ====================

    private static String formatArgs(Object args) {
        if (args == null) return "{}";
        String s = String.valueOf(args);
        if (s.length() > 200) return s.substring(0, 200) + "...";
        return s;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
