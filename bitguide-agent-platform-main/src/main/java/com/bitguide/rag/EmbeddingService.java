package com.bitguide.rag;

import com.bitguide.config.RagConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding 服务 - 使用 Ollama 生成文本向量
 */
public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final RagConfig.EmbeddingConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingService(RagConfig.EmbeddingConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 生成单个文本的向量
     */
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    /**
     * 批量生成文本向量
     */
    public List<float[]> embedBatch(List<String> texts) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModelName());
            requestBody.put("input", texts);

            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl() + "/api/embed"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode embeddingsNode = root.get("embeddings");
                
                List<float[]> embeddings = new ArrayList<>();
                if (embeddingsNode != null && embeddingsNode.isArray()) {
                    for (JsonNode embeddingNode : embeddingsNode) {
                        float[] embedding = new float[embeddingNode.size()];
                        for (int i = 0; i < embeddingNode.size(); i++) {
                            embedding[i] = (float) embeddingNode.get(i).asDouble();
                        }
                        embeddings.add(embedding);
                    }
                }
                log.debug("Generated {} embeddings with dimension {}", embeddings.size(), 
                        embeddings.isEmpty() ? 0 : embeddings.get(0).length);
                return embeddings;
            } else {
                log.error("Ollama embedding API error: {} - {}", response.statusCode(), response.body());
                throw new RuntimeException("Embedding API failed: " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("Failed to generate embeddings", e);
            throw new RuntimeException("Failed to generate embeddings", e);
        }
    }

    /**
     * 获取向量维度
     */
    public int getDimension() {
        return config.getDimension();
    }
}