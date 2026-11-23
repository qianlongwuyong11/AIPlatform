package com.bitguide.rag;

import com.alibaba.fastjson.JSONObject;
import com.bitguide.config.RagConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.response.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Milvus 向量存储服务
 */
public class MilvusVectorStore {
    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);
    private static final Gson GSON = new Gson();

    private static final String ID_FIELD = "id";
    private static final String CONTENT_FIELD = "content";
    private static final String EMBEDDING_FIELD = "embedding";
    private static final String METADATA_FIELD = "metadata";

    private final MilvusClientV2 milvusClient;
    private final RagConfig.VectorStoreConfig config;
    private final int dimension;

    public MilvusVectorStore(RagConfig.VectorStoreConfig config, int dimension) {
        this.config = config;
        this.dimension = dimension;
        this.milvusClient = createClient();
        log.info("Milvus client initialized: {}:{}", config.getHost(), config.getPort());
    }

    private MilvusClientV2 createClient() {
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri("http://" + config.getHost() + ":" + config.getPort())
                .build();
        return new MilvusClientV2(connectConfig);
    }

    /**
     * 获取集合名称
     */
    private String getCollectionName(String knowledgeBaseId) {
        return config.getCollectionPrefix() + knowledgeBaseId;
    }

    /**
     * 创建知识库集合
     */
    public void createCollection(String knowledgeBaseId) {
        String collectionName = getCollectionName(knowledgeBaseId);
        
        // 检查集合是否已存在
        boolean hasCollection = milvusClient.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        
        if (hasCollection) {
            log.info("Collection {} already exists", collectionName);
            return;
        }

        // 创建字段 schema
        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name(ID_FIELD)
                .dataType(DataType.VarChar)
                .maxLength(256)
                .isPrimaryKey(true)
                .autoID(false)
                .build();

        CreateCollectionReq.FieldSchema contentField = CreateCollectionReq.FieldSchema.builder()
                .name(CONTENT_FIELD)
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name(EMBEDDING_FIELD)
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .build();

        CreateCollectionReq.FieldSchema metadataField = CreateCollectionReq.FieldSchema.builder()
                .name(METADATA_FIELD)
                .dataType(DataType.JSON)
                .build();

        // 创建集合 schema - 使用 fieldSchemasList 方法
        List<CreateCollectionReq.FieldSchema> fieldSchemas = Arrays.asList(
                idField, contentField, embeddingField, metadataField
        );
        
        CreateCollectionReq.CollectionSchema collectionSchema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(fieldSchemas)
                .build();

        CreateCollectionReq createCollectionReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description("Knowledge base: " + knowledgeBaseId)
                .collectionSchema(collectionSchema)
                .build();
        
        milvusClient.createCollection(createCollectionReq);
        log.info("Created collection: {}", collectionName);

        // 创建向量索引
        IndexParam indexParam = IndexParam.builder()
                .fieldName(EMBEDDING_FIELD)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        CreateIndexReq createIndexReq = CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(indexParam))
                .build();
        
        milvusClient.createIndex(createIndexReq);
        log.info("Created index for collection: {}", collectionName);
    }

    /**
     * 插入文档
     */
    public void insert(String knowledgeBaseId, List<Document> documents) {
        if (documents.isEmpty()) {
            return;
        }

        String collectionName = getCollectionName(knowledgeBaseId);
        
        // 转换为 JSONObject 列表
        List<JSONObject> dataList = new ArrayList<>();
        for (Document doc : documents) {
            JSONObject data = new JSONObject();
            data.put(ID_FIELD, doc.getId());
            data.put(CONTENT_FIELD, doc.getContent());
            data.put(EMBEDDING_FIELD, toList(doc.getEmbedding()));
            // 将 Gson JsonObject 转换为字符串再转为 fastjson JSONObject
            data.put(METADATA_FIELD, JSONObject.parseObject(doc.getMetadata().toString()));
            dataList.add(data);
        }

        // 加载集合到内存
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());

        // 插入数据
        InsertReq insertReq = InsertReq.builder()
                .collectionName(collectionName)
                .data(dataList)
                .build();

        milvusClient.insert(insertReq);
        log.info("Inserted {} documents into collection {}", documents.size(), collectionName);
    }

    /**
     * 将 float[] 转换为 List<Float>
     */
    private List<Float> toList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) {
            list.add(f);
        }
        return list;
    }

    /**
     * 相似度搜索
     */
    public List<SearchResult> search(String knowledgeBaseId, float[] queryEmbedding, int topK) {
        String collectionName = getCollectionName(knowledgeBaseId);

        // 加载集合到内存
        milvusClient.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());

        // 搜索 - 使用 List<Float> 作为向量数据
        List<Float> queryVector = toList(queryEmbedding);
        
        SearchReq searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(queryVector))
                .annsField(EMBEDDING_FIELD)
                .topK(topK)
                .outputFields(List.of(CONTENT_FIELD, METADATA_FIELD))
                .build();

        SearchResp searchResp = milvusClient.search(searchReq);

        List<SearchResult> results = new ArrayList<>();
        // getSearchResults() 返回 List<List<SearchResult>>，需要展平
        List<List<SearchResp.SearchResult>> searchResultsList = searchResp.getSearchResults();
        
        for (List<SearchResp.SearchResult> resultList : searchResultsList) {
            for (SearchResp.SearchResult result : resultList) {
                String id = String.valueOf(result.getId());
                float score = result.getDistance();
                Map<String, Object> entity = result.getEntity();
                
                String content = entity.containsKey(CONTENT_FIELD) ? String.valueOf(entity.get(CONTENT_FIELD)) : "";
                
                // 处理 metadata
                JsonObject metadata = new JsonObject();
                Object metadataObj = entity.get(METADATA_FIELD);
                if (metadataObj != null) {
                    try {
                        metadata = GSON.fromJson(metadataObj.toString(), JsonObject.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse metadata: {}", metadataObj);
                    }
                }
                
                results.add(new SearchResult(id, content, score, metadata));
            }
        }

        log.debug("Found {} results for query in collection {}", results.size(), collectionName);
        return results;
    }

    /**
     * 删除集合
     */
    public void dropCollection(String knowledgeBaseId) {
        String collectionName = getCollectionName(knowledgeBaseId);
        milvusClient.releaseCollection(ReleaseCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        milvusClient.dropCollection(DropCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        log.info("Dropped collection: {}", collectionName);
    }

    /**
     * 关闭连接
     */
    public void close() {
        if (milvusClient != null) {
            try {
                milvusClient.close(0);
                log.info("Milvus client closed");
            } catch (Exception e) {
                log.warn("Error closing Milvus client", e);
            }
        }
    }

    /**
     * 文档对象
     */
    public static class Document {
        private final String id;
        private final String content;
        private final float[] embedding;
        private final JsonObject metadata;

        public Document(String id, String content, float[] embedding, JsonObject metadata) {
            this.id = id;
            this.content = content;
            this.embedding = embedding;
            this.metadata = metadata;
        }

        public String getId() { return id; }
        public String getContent() { return content; }
        public float[] getEmbedding() { return embedding; }
        public JsonObject getMetadata() { return metadata; }
    }

    /**
     * 搜索结果
     */
    public static class SearchResult {
        private final String id;
        private final String content;
        private final float score;
        private final JsonObject metadata;

        public SearchResult(String id, String content, float score, JsonObject metadata) {
            this.id = id;
            this.content = content;
            this.score = score;
            this.metadata = metadata;
        }

        public String getId() { return id; }
        public String getContent() { return content; }
        public float getScore() { return score; }
        public JsonObject getMetadata() { return metadata; }
    }
}