package com.bitguide.config;

import lombok.Data;

/**
 * 模型配置
 */
@Data
public class ModelConfig {
    private String provider;
    private String modelName;
    private String apiKey;
    private String baseUrl;
    private Double temperature = 0.7;
    private Integer maxTokens = 2000;
}