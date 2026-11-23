package com.bitguide.config;

import lombok.Data;

import java.util.List;

/**
 * 知识库配置
 */
@Data
public class KnowledgeBaseConfig {
    private String id;
    private String name;
    private String description;
    private List<SourceConfig> sources;

    /**
     * 文档来源配置
     */
    @Data
    public static class SourceConfig {
        private String type;  // file, url
        private String path;  // 用于 file 类型
        private List<String> extensions;  // 用于 file 类型
        private List<String> urls;  // 用于 url 类型
    }
}