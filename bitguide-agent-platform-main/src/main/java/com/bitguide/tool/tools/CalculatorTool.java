package com.bitguide.tool.tools;

import com.bitguide.tool.Tool;
import com.bitguide.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 计算器工具
 * 执行数学表达式计算（纯 Java 实现）
 */
@Slf4j
public class CalculatorTool implements Tool {
    
    @Override
    public String getName() {
        return "calculator";
    }
    
    @Override
    public String getDescription() {
        return "执行数学表达式计算。输入一个数学表达式（如 '2 + 3 * 4'），返回计算结果。";
    }
    
    @Override
    public String getParametersSchema() {
        return """
            {
              "type": "object",
              "properties": {
                "expression": {
                  "type": "string",
                  "description": "要计算的数学表达式，如 '2 + 3 * 4' 或 '(10 - 2) / 4'"
                }
              },
              "required": ["expression"]
            }
            """;
    }
    
    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            String expression = (String) params.get("expression");
            if (expression == null || expression.trim().isEmpty()) {
                return ToolResult.failure("表达式不能为空");
            }
            
            log.debug("执行计算: {}", expression);
            
            // 安全检查：只允许数学相关字符
            if (!isValidExpression(expression)) {
                return ToolResult.failure("表达式包含不允许的字符");
            }
            
            double result = evaluate(expression);
            
            // 如果是整数，返回整数格式
            if (result == (long) result) {
                log.debug("计算结果: {} = {}", expression, (long) result);
                return ToolResult.success(String.valueOf((long) result));
            } else {
                log.debug("计算结果: {} = {}", expression, result);
                return ToolResult.success(String.valueOf(result));
            }
            
        } catch (Exception e) {
            log.error("计算失败: {}", e.getMessage());
            return ToolResult.failure("计算失败: " + e.getMessage());
        }
    }
    
    /**
     * 验证表达式是否安全
     */
    private boolean isValidExpression(String expression) {
        // 只允许数字、运算符、括号、小数点、空格
        String sanitized = expression.replaceAll("\\s+", "");
        return sanitized.matches("^[0-9+\\-*/().]+$");
    }
    
    /**
     * 计算表达式（支持 + - * / 和括号）
     */
    private double evaluate(String expression) {
        // 移除空格
        expression = expression.replaceAll("\\s+", "");
        return new ExpressionParser(expression).parse();
    }
    
    /**
     * 简单的表达式解析器
     * 支持 + - * / 和括号
     */
    private static class ExpressionParser {
        private final String expression;
        private int pos = 0;
        
        public ExpressionParser(String expression) {
            this.expression = expression;
        }
        
        public double parse() {
            return parseExpression();
        }
        
        private double parseExpression() {
            double result = parseTerm();
            
            while (pos < expression.length()) {
                char op = expression.charAt(pos);
                if (op == '+') {
                    pos++;
                    result += parseTerm();
                } else if (op == '-') {
                    pos++;
                    result -= parseTerm();
                } else {
                    break;
                }
            }
            
            return result;
        }
        
        private double parseTerm() {
            double result = parseFactor();
            
            while (pos < expression.length()) {
                char op = expression.charAt(pos);
                if (op == '*') {
                    pos++;
                    result *= parseFactor();
                } else if (op == '/') {
                    pos++;
                    double divisor = parseFactor();
                    if (divisor == 0) {
                        throw new ArithmeticException("除数不能为零");
                    }
                    result /= divisor;
                } else {
                    break;
                }
            }
            
            return result;
        }
        
        private double parseFactor() {
            // 跳过空格
            while (pos < expression.length() && expression.charAt(pos) == ' ') {
                pos++;
            }
            
            if (pos >= expression.length()) {
                return 0;
            }
            
            char ch = expression.charAt(pos);
            
            // 处理负号
            if (ch == '-') {
                pos++;
                return -parseFactor();
            }
            
            // 处理正号
            if (ch == '+') {
                pos++;
                return parseFactor();
            }
            
            // 处理括号
            if (ch == '(') {
                pos++;
                double result = parseExpression();
                if (pos < expression.length() && expression.charAt(pos) == ')') {
                    pos++;
                }
                return result;
            }
            
            // 解析数字
            StringBuilder sb = new StringBuilder();
            while (pos < expression.length()) {
                ch = expression.charAt(pos);
                if (Character.isDigit(ch) || ch == '.') {
                    sb.append(ch);
                    pos++;
                } else {
                    break;
                }
            }
            
            if (sb.length() == 0) {
                return 0;
            }
            
            return Double.parseDouble(sb.toString());
        }
    }
}