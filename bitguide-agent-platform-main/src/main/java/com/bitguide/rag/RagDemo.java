package com.bitguide.rag;

import com.bitguide.config.BitGuideConfig;
import com.bitguide.config.KnowledgeBaseConfig;
import com.bitguide.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * RAG 功能演示
 * 
 * 使用方法：
 * 1. 启动 Docker 服务：cd docker && docker-compose up -d
 * 2. 下载 embedding 模型：docker exec -it bitguide-ollama ollama pull nomic-embed-text
 * 3. 运行此类的 main 方法
 */
public class RagDemo {
    private static final Logger log = LoggerFactory.getLogger(RagDemo.class);

    public static void main(String[] args) {
        log.info("=== BitGuide RAG 功能演示 ===");
        
        // 1. 加载配置
        log.info("步骤 1: 加载配置...");
        BitGuideConfig config = ConfigLoader.load("src/stage3/java/com/bitguide/stage3/config/stage3.yml");
        
        if (config.getRag() == null) {
            log.error("RAG 配置未找到，请检查配置文件");
            return;
        }
        
        // 2. 初始化 RAG 服务
        log.info("步骤 2: 初始化 RAG 服务...");
        RagService ragService = new RagService(config.getRag());
        
        try {
            // 3. 初始化知识库
            log.info("步骤 3: 初始化知识库...");
            if (config.getKnowledgeBases() != null) {
                for (KnowledgeBaseConfig kbConfig : config.getKnowledgeBases().values()) {
                    try {
                        ragService.initKnowledgeBase(kbConfig);
                    } catch (Exception e) {
                        log.warn("初始化知识库 {} 失败: {}", kbConfig.getId(), e.getMessage());
                    }
                }
            }
            
            // 4. 手动添加一些测试文档
            log.info("步骤 4: 添加测试文档...");
            addTestDocuments(ragService);
            
            // 5. 交互式查询
            log.info("步骤 5: 开始交互式查询（输入 'quit' 退出）...");
            runInteractiveQuery(ragService);
            
        } finally {
            ragService.close();
        }
        
        log.info("演示结束");
    }

    /**
     * 添加测试文档
     */
    private static void addTestDocuments(RagService ragService) {
        String knowledgeBaseId = "parenting_docs";
        
        // 添加一些关于 BitGuide 的文档
        ragService.addText(knowledgeBaseId, 
                "BitGuide 是一个可配置化的 AI Agent 平台。" +
                "它支持配置驱动的方式创建智能助手，无需编写代码。" +
                "主要特点包括：多模型支持、工具调用、RAG 知识库检索。",
                Map.of("category", "产品介绍"));
        
        ragService.addText(knowledgeBaseId,
                "BitGuide 支持多种大语言模型，包括 Ollama 本地模型、智谱 AI 的 GLM 系列，" +
                "以及其他兼容 OpenAI API 的模型。用户可以根据需求选择合适的模型。",
                Map.of("category", "模型支持"));
        
        ragService.addText(knowledgeBaseId,
                "RAG（Retrieval-Augmented Generation）是一种结合检索和生成的技术。" +
                "BitGuide 使用 Milvus 作为向量数据库，支持文档的自动分割和相似度检索。",
                Map.of("category", "RAG 功能"));
        
        ragService.addText(knowledgeBaseId,
                "要启动 BitGuide，首先需要运行 Docker 服务，然后下载 Ollama 模型。" +
                "支持的模型包括 qwen2.5 用于对话，nomic-embed-text 用于文本向量化。",
                Map.of("category", "使用指南"));
        
        log.info("测试文档添加完成");
    }

    /**
     * 交互式查询
     */
    private static void runInteractiveQuery(RagService ragService) {
        Scanner scanner = new Scanner(System.in);
        String knowledgeBaseId = "parenting_docs";
        
        System.out.println("\n========================================");
        System.out.println("RAG 交互式查询演示");
        System.out.println("知识库: parenting_docs");
        System.out.println("输入问题进行查询，输入 'quit' 退出");
        System.out.println("========================================\n");
        
        while (true) {
            System.out.print("请输入问题: ");
            String query = scanner.nextLine().trim();
            
            if (query.equalsIgnoreCase("quit") || query.equalsIgnoreCase("exit")) {
                break;
            }
            
            if (query.isEmpty()) {
                continue;
            }
            
            // 检索相关文档
            System.out.println("\n正在检索相关文档...");
            List<RagService.RetrievedDocument> documents = ragService.retrieve(knowledgeBaseId, query);
            
            if (documents.isEmpty()) {
                System.out.println("未找到相关文档");
            } else {
                System.out.println("找到 " + documents.size() + " 个相关文档:\n");
                
                for (int i = 0; i < documents.size(); i++) {
                    RagService.RetrievedDocument doc = documents.get(i);
                    System.out.println("【文档 " + (i + 1) + "】(相似度: " + 
                            String.format("%.2f", doc.getScore()) + ")");
                    System.out.println(doc.getContent());
                    System.out.println();
                }
            }
            
            // 构建 RAG 上下文
            System.out.println("----------------------------------------");
            System.out.println("RAG 上下文:");
            String context = ragService.buildContext(knowledgeBaseId, query);
            System.out.println(context.isEmpty() ? "无相关上下文" : context);
            System.out.println("========================================\n");
        }
        
        scanner.close();
        System.out.println("退出交互式查询");
    }
}