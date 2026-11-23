package com.bitguide.rag;

import com.bitguide.log.ConsoleLog;
import com.bitguide.config.KnowledgeBaseConfig;
import com.bitguide.config.RagConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RAG 服务 - 整合文档处理、向量化和检索功能
 */
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final RagConfig ragConfig;
    private final EmbeddingService embeddingService;
    private final MilvusVectorStore vectorStore;
    private final DocumentSplitter documentSplitter;
    private final Gson gson;

    public RagService(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
        this.embeddingService = new EmbeddingService(ragConfig.getEmbedding());
        this.vectorStore = new MilvusVectorStore(ragConfig.getVectorStore(), ragConfig.getEmbedding().getDimension());
        this.documentSplitter = new DocumentSplitter(ragConfig.getSplitter());
        this.gson = new Gson();
        
        log.info("RagService initialized with embedding dimension: {}", ragConfig.getEmbedding().getDimension());
    }

    /**
     * 初始化知识库，返回总分块数
     */
    public int initKnowledgeBase(KnowledgeBaseConfig kbConfig) {
        String kbId = kbConfig.getId();
        log.info("Initializing knowledge base: {} ({})", kbConfig.getName(), kbId);
        int totalChunks = 0;

        // 创建向量集合
        vectorStore.createCollection(kbId);

        // 处理文档来源
        if (kbConfig.getSources() != null) {
            for (KnowledgeBaseConfig.SourceConfig source : kbConfig.getSources()) {
                if ("file".equals(source.getType())) {
                    totalChunks += loadDocumentsFromPath(kbId, source.getPath(), source.getExtensions());
                } else if ("url".equals(source.getType())) {
                    // TODO: 实现 URL 文档加载
                    log.warn("URL source type not yet implemented");
                }
            }
        }

        ConsoleLog.info("[RAG]", "知识库 [%s] 初始化完成", kbId);
        return totalChunks;
    }

    /**
     * 从文件路径加载文档
     */
    public int loadDocumentsFromPath(String knowledgeBaseId, String path, List<String> extensions) {
        ConsoleLog.info("[RAG]", "加载文档目录: %s", path);

        // 确保集合存在
        vectorStore.createCollection(knowledgeBaseId);

        Path rootPath = Paths.get(path);
        if (!Files.exists(rootPath)) {
            ConsoleLog.warn("[RAG]", "目录不存在: %s", path);
            return 0;
        }

        int totalChunks = 0;
        try (Stream<Path> paths = Files.walk(rootPath)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> isExtensionMatch(p, extensions))
                    .collect(Collectors.toList());

            ConsoleLog.info("[RAG]", "发现 %d 个文档文件", files.size());

            for (Path file : files) {
                try {
                    totalChunks += loadDocument(knowledgeBaseId, file);
                } catch (Exception e) {
                    log.error("Failed to load document: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk path: {}", path, e);
        }
        return totalChunks;
    }

    /**
     * 加载单个文档，返回分块数
     */
    public int loadDocument(String knowledgeBaseId, Path filePath) throws IOException {
        log.debug("Loading document: {}", filePath);

        String content = Files.readString(filePath);
        String fileName = filePath.getFileName().toString();

        // 分割文档
        List<String> segments = documentSplitter.split(content);
        log.debug("Document {} split into {} segments", fileName, segments.size());

        if (segments.isEmpty()) {
            return 0;
        }

        // 批量生成向量
        List<float[]> embeddings = embeddingService.embedBatch(segments);
        
        // 创建文档对象
        List<MilvusVectorStore.Document> documents = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String id = UUID.nameUUIDFromBytes((fileName + "_" + i).getBytes()).toString();
            
            JsonObject metadata = new JsonObject();
            metadata.addProperty("source", fileName);
            metadata.addProperty("path", filePath.toString());
            metadata.addProperty("segment_index", i);
            
            documents.add(new MilvusVectorStore.Document(
                    id,
                    segments.get(i),
                    embeddings.get(i),
                    metadata
            ));
        }

        // 插入向量存储
        vectorStore.insert(knowledgeBaseId, documents);
        ConsoleLog.info("[RAG]", "文档加载完成: %s (%d 个分块)", fileName, segments.size());
        return segments.size();
    }

    /**
     * 添加文本内容到知识库
     */
    public void addText(String knowledgeBaseId, String content, Map<String, Object> metadata) {
        // 分割文本
        List<String> segments = documentSplitter.split(content);
        
        if (segments.isEmpty()) {
            return;
        }

        // 生成向量
        List<float[]> embeddings = embeddingService.embedBatch(segments);
        
        // 创建文档对象
        List<MilvusVectorStore.Document> documents = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String id = UUID.randomUUID().toString();
            
            JsonObject jsonMetadata = new JsonObject();
            if (metadata != null) {
                jsonMetadata = gson.toJsonTree(metadata).getAsJsonObject();
            }
            jsonMetadata.addProperty("segment_index", i);
            
            documents.add(new MilvusVectorStore.Document(
                    id,
                    segments.get(i),
                    embeddings.get(i),
                    jsonMetadata
            ));
        }

        // 插入向量存储
        vectorStore.insert(knowledgeBaseId, documents);
        log.info("Added {} text segments to knowledge base {}", segments.size(), knowledgeBaseId);
    }

    /**
     * 检索相关文档
     */
    public List<RetrievedDocument> retrieve(String knowledgeBaseId, String query) {
        return retrieve(knowledgeBaseId, query, ragConfig.getRetrieval().getTopK());
    }

    /**
     * 检索相关文档（指定数量）
     */
    public List<RetrievedDocument> retrieve(String knowledgeBaseId, String query, int topK) {
        log.debug("Retrieving documents for query: {} (topK={})", query, topK);
        
        // 生成查询向量
        float[] queryEmbedding = embeddingService.embed(query);
        
        // 搜索
        List<MilvusVectorStore.SearchResult> results = vectorStore.search(knowledgeBaseId, queryEmbedding, topK);
        
        // 过滤低分结果
        double threshold = ragConfig.getRetrieval().getScoreThreshold();
        List<RetrievedDocument> documents = new ArrayList<>();
        
        for (MilvusVectorStore.SearchResult result : results) {
            if (result.getScore() >= threshold) {
                Map<String, Object> metadata = new HashMap<>();
                if (result.getMetadata() != null) {
                    for (String key : result.getMetadata().keySet()) {
                        metadata.put(key, result.getMetadata().get(key));
                    }
                }
                
                documents.add(new RetrievedDocument(
                        result.getId(),
                        result.getContent(),
                        result.getScore(),
                        metadata
                ));
            }
        }
        
        log.debug("Retrieved {} documents (threshold={})", documents.size(), threshold);
        return documents;
    }

    /**
     * 构建 RAG 上下文
     */
    public String buildContext(String knowledgeBaseId, String query) {
        List<RetrievedDocument> documents = retrieve(knowledgeBaseId, query);
        
        if (documents.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("以下是从知识库中检索到的相关内容：\n\n");
        
        for (int i = 0; i < documents.size(); i++) {
            RetrievedDocument doc = documents.get(i);
            context.append("【文档 ").append(i + 1).append("】\n");
            context.append(doc.getContent()).append("\n\n");
        }
        
        return context.toString();
    }

    /**
     * 检查文件扩展名是否匹配
     */
    private boolean isExtensionMatch(Path path, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return true;
        }

        String fileName = path.getFileName().toString().toLowerCase();
        return extensions.stream()
                .anyMatch(ext -> fileName.endsWith(ext.toLowerCase()));
    }

    /**
     * 删除指定知识库的所有向量
     * @param kbId 知识库 ID
     */
    public void deleteKnowledgeBase(String kbId) {
        try {
            vectorStore.dropCollection(kbId);
            log.info("知识库 [{}] 索引已删除", kbId);
        } catch (Exception e) {
            log.debug("知识库 [{}] 索引删除跳过（可能不存在）: {}", kbId, e.getMessage());
        }
    }

    /**
     * 关闭服务
     */
    public void close() {
        vectorStore.close();
        log.info("RagService closed");
    }

    /**
     * 检索到的文档
     */
    public static class RetrievedDocument {
        private final String id;
        private final String content;
        private final double score;
        private final Map<String, Object> metadata;

        public RetrievedDocument(String id, String content, double score, Map<String, Object> metadata) {
            this.id = id;
            this.content = content;
            this.score = score;
            this.metadata = metadata;
        }

        public String getId() { return id; }
        public String getContent() { return content; }
        public double getScore() { return score; }
        public Map<String, Object> getMetadata() { return metadata; }

        @Override
        public String toString() {
            return "RetrievedDocument{" +
                    "id='" + id + '\'' +
                    ", score=" + score +
                    ", content='" + content.substring(0, Math.min(100, content.length())) + "...'"+
                    '}';
        }
    }
}