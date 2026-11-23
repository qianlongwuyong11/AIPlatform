package com.bitguide.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器
 */
@Slf4j
public class ConfigLoader {
    
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");
    
    /**
     * 从文件加载配置
     */
    public static BitGuideConfig load(String configPath) {
        try {
            File file = new File(configPath);
            if (!file.exists()) {
                throw new RuntimeException("配置文件不存在: " + configPath);
            }
            log.info("加载配置文件: {}", configPath);
            
            // 读取文件内容并解析环境变量
            String content = Files.readString(file.toPath());
            String resolvedContent = resolveEnvironmentVariables(content);
            
            return YAML_MAPPER.readValue(resolvedContent, BitGuideConfig.class);
        } catch (IOException e) {
            log.error("加载配置文件失败: {}", e.getMessage());
            throw new RuntimeException("加载配置文件失败", e);
        }
    }
    
    /**
     * 解析环境变量
     * 支持 ${ENV_VAR} 和 ${ENV_VAR:default} 格式
     */
    private static String resolveEnvironmentVariables(String content) {
        Matcher matcher = ENV_PATTERN.matcher(content);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String defaultValue = matcher.group(2);
            
            String value = System.getenv(envVar);
            if (value == null || value.isEmpty()) {
                value = defaultValue != null ? defaultValue : "";
            }
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
}
