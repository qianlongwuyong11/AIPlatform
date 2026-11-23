package com.bitguide.config;

import lombok.Data;

import java.util.List;

/**
 * RAG 配置
 */
@Data
public class RagConfig {
    private boolean enabled = true;
    private VectorStoreConfig vectorStore;
    private EmbeddingConfig embedding;
    private SplitterConfig splitter;
    private RetrievalConfig retrieval;

    /**
     * 向量数据库配置
     */
    @Data
    public static class VectorStoreConfig {
        private String type = "milvus";
        private String host = "localhost";
        private int port = 19530;
        private String collectionPrefix = "bitguide_";
    }

    /**
     * Embedding 配置
     */
    @Data
    public static class EmbeddingConfig {
        private String provider = "ollama";
        private String baseUrl = "http://localhost:11434";
        private String modelName = "nomic-embed-text";
        private int dimension = 768;
    }

    /**
     * 文档分割配置
     */
    @Data
    public static class SplitterConfig {
        private int maxSegmentSize = 500;
        private int overlap = 50;
    }

    /**
     * 检索配置
     */
    @Data
    public static class RetrievalConfig {
        private int topK = 5;
        private double scoreThreshold = 0.7;
    }
}