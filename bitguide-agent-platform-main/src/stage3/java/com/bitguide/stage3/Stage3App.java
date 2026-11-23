package com.bitguide.stage3;

import com.bitguide.agent.AgentFactory;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.config.BitGuideConfig;
import com.bitguide.config.ConfigLoader;
import com.bitguide.config.RedisConfig;
import com.bitguide.config.dynamic.DynamicConfigManager;
import com.bitguide.config.dynamic.EtcdConfig;
import com.bitguide.config.dynamic.EtcdConfigWatcher;
import com.bitguide.gateway.config.GatewayConfig;
import com.bitguide.gateway.distributed.DistributedAgentGateway;
import com.bitguide.llm.LLMGateway;
import com.bitguide.memory.ConversationMemory;
import com.bitguide.memory.DistributedMemory;
import com.bitguide.rag.RagService;
import com.bitguide.redis.RedisClient;
import com.bitguide.stage3.config.ChatMetrics;
import com.bitguide.stage3.server.controller.AgentApiController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.Map;

/**
 * Stage 3: 企业级 Agent HTTP 服务 (Spring Boot)
 * 
 * 提供完整的 HTTP API 服务，支持：
 * - 分布式会话管理（Redis）
 * - 分布式限流（Redis）
 * - 分布式 Lane 管理（Redis）
 * - RESTful API
 * - Web 前端界面
 * 
 * 教学重点：
 * - 理解企业级 Agent 服务的架构设计
 * - 掌握分布式系统的核心组件
 * - 学习 HTTP API 设计最佳实践
 */
@Slf4j
@SpringBootApplication
public class Stage3App {
    
    private static final int DEFAULT_PORT = 8080;
    
    private static BitGuideConfig config;
    private static RedisConfig redisConfig;
    private static int port;
    
    public static void main(String[] args) {
        try {
            // 解析端口参数
            port = parsePort(args);
            
            // 1. 加载配置
            log.info("========================================");
            log.info("  Stage 3: 企业级 Agent HTTP 服务");
            log.info("========================================\n");
            
            log.info("[1] 加载配置文件...");
            config = ConfigLoader.load("src/stage3/java/com/bitguide/stage3/config/stage3.yml");
            log.info("    ✓ 配置加载成功");
            log.info("    - Agent数量: {}", config.getAgents().size());
            
            // 2. 解析 Redis 配置
            log.info("\n[2] 解析 Redis 配置...");
            redisConfig = parseRedisConfig();
            log.info("    - Redis 启用: {}", redisConfig.isEnabled());
            
            // 设置端口
            System.setProperty("server.port", String.valueOf(port));

            // 开启 Prometheus 指标端点
            System.setProperty("management.endpoints.web.exposure.include", "health,info,prometheus,metrics");
            System.setProperty("management.endpoint.prometheus.enabled", "true");
            System.setProperty("management.endpoints.web.base-path", "/actuator");
            
            log.info("\n[3] 启动 Spring Boot...");
            log.info("    - 端口: {}", port);
            
            // 启动 Spring Boot
            SpringApplication.run(Stage3App.class, args);
            
        } catch (Exception e) {
            log.error("启动失败", e);
            System.exit(1);
        }
    }
    
    /**
     * 配置 Redis 客户端
     */
    @Bean
    public RedisClient redisClient() {
        if (redisConfig.isEnabled()) {
            RedisClient client = new RedisClient(redisConfig);
            if (client.isHealthy()) {
                log.info("    ✓ Redis 连接成功: {}:{}", redisConfig.getHost(), redisConfig.getPort());
                return client;
            } else {
                log.warn("    ! Redis 连接失败，将使用本地模式");
                return null;
            }
        }
        log.info("    - Redis 未启用，使用本地模式");
        return null;
    }
    
    /**
     * 配置 LLM 网关
     */
    @Bean
    public LLMGateway llmGateway() {
        log.info("    ✓ LLM 网关初始化完成");
        return new LLMGateway();
    }
    
    /**
     * 配置 RAG 服务
     */
    @Bean
    public RagService ragService() {
        if (config.getRag() != null && config.getRag().isEnabled()) {
            try {
                RagService service = new RagService(config.getRag());
                log.info("    ✓ RAG 服务初始化完成");
                return service;
            } catch (Exception e) {
                log.warn("    ! RAG 服务初始化失败: {}", e.getMessage());
                return null;
            }
        }
        log.info("    - RAG 未启用");
        return null;
    }
    
    /**
     * 配置分布式内存
     */
    @Bean
    @Nullable
    public DistributedMemory distributedMemory(@Nullable RedisClient redisClient) {
        if (redisConfig.isEnabled() && redisClient != null) {
            DistributedMemory memory = new DistributedMemory(redisClient, redisConfig);
            log.info("    ✓ 分布式会话存储初始化完成");
            return memory;
        }
        return null;
    }
    
    /**
     * 配置本地会话存储
     */
    @Bean
    public ConversationMemory conversationMemory() {
        log.info("    ✓ 本地会话存储初始化完成");
        return new ConversationMemory();
    }
    
    /**
     * 配置 Etcd 配置
     */
    @Bean
    public EtcdConfig etcdConfig() {
        EtcdConfig etcdConfig = config.getEtcd();
        if (etcdConfig == null) {
            etcdConfig = new EtcdConfig();
        }
        log.info("    ✓ Etcd 配置: enabled={}, endpoints={}", 
                etcdConfig.isEnabled(), 
                etcdConfig.isEnabled() ? Arrays.toString(etcdConfig.getEndpoints()) : "N/A");
        return etcdConfig;
    }
    
    /**
     * 配置 Etcd 配置监听器
     */
    @Bean
    public EtcdConfigWatcher etcdConfigWatcher(EtcdConfig etcdConfig) {
        return new EtcdConfigWatcher(etcdConfig);
    }
    
    /**
     * 配置共享的 AgentFactory（单例）
     */
    @Bean
    public AgentFactory agentFactory(LLMGateway llmGateway, RagService ragService, ConversationMemory conversationMemory) {
        log.info("    ✓ AgentFactory 初始化...");
        return new AgentFactory(config, llmGateway, ragService, conversationMemory);
    }

    /**
     * 配置 Agent 注册表（不预注册静态 Agent，由动态配置管理）
     */
    @Bean
    public AgentRegistry agentRegistry(AgentFactory agentFactory) {
        log.info("    ✓ Agent 注册表初始化...");
        AgentRegistry registry = new AgentRegistry(agentFactory);

        log.info("    - Agent 将通过 etcd 动态加载");
        return registry;
    }

    /**
     * 配置动态配置管理器（启动后自动从 etcd 加载 Agent）
     */
    @Bean
    public DynamicConfigManager dynamicConfigManager(
            EtcdConfigWatcher etcdConfigWatcher,
            AgentRegistry agentRegistry,
            AgentFactory agentFactory,
            @Nullable RagService ragService) {
        log.info("    ✓ 动态配置管理器初始化...");

        DynamicConfigManager manager = new DynamicConfigManager(etcdConfigWatcher, agentRegistry, agentFactory);

        // 注入 RAG 服务（etcd 推送知识库配置时触发文档摄取）
        if (ragService != null) {
            manager.setRagService(ragService);
        }

        // 注入技能管理器（etcd 推送技能配置时动态注册）
        if (agentFactory.getSkillManager() != null) {
            manager.setSkillManager(agentFactory.getSkillManager());
        }

        manager.init();
        
        log.info("    - 可用 Agent: {}", agentRegistry.getAvailableAgentIds());
        return manager;
    }
    
    /**
     * 配置网关配置
     */
    @Bean
    public GatewayConfig gatewayConfig() {
        return createGatewayConfig();
    }
    
    /**
     * 配置分布式网关
     */
    @Bean
    @Nullable
    public DistributedAgentGateway distributedAgentGateway(
            AgentRegistry agentRegistry,
            @Nullable DistributedMemory distributedMemory,
            @Nullable RedisClient redisClient,
            ConversationMemory conversationMemory) {
        log.info("    ✓ 网关初始化...");
        
        if (redisConfig.isEnabled() && redisClient != null && distributedMemory != null) {
            log.info("    ✓ 分布式网关初始化完成");
            return new DistributedAgentGateway(
                    agentRegistry, distributedMemory, redisClient, redisConfig, gatewayConfig()
            );
        }
        log.info("    ✓ 本地模式网关初始化完成");
        return null;
    }
    
    /**
     * 配置 API 控制器
     */
    @Bean
    public AgentApiController agentApiController(
            @Nullable DistributedAgentGateway gateway,
            AgentRegistry agentRegistry,
            ConversationMemory conversationMemory,
            ChatMetrics chatMetrics) {
        return new AgentApiController(gateway, agentRegistry, conversationMemory, chatMetrics);
    }

    /**
     * 启动完成后打印访问地址
     */
    @Bean
    public CommandLineRunner startupBanner() {
        return args -> {
            System.out.println();
            System.out.println("========================================");
            System.out.println("  Stage 3 服务已启动");
            System.out.println("========================================");
            System.out.println();
            System.out.println("  端点:");
            System.out.printf("    - Web 界面:     http://localhost:%d/\n", port);
            System.out.printf("    - 健康检查:     http://localhost:%d/health\n", port);
            System.out.printf("    - Agent 列表:   http://localhost:%d/api/v1/agents\n", port);
            System.out.printf("    - Chat API:     POST http://localhost:%d/api/v1/chat\n", port);
            System.out.printf("    - Prometheus:   http://localhost:%d/actuator/prometheus\n", port);
            System.out.println();
        };
    }

    /**
     * 创建本地网关（当 Redis 不可用时）
     */
    private DistributedAgentGateway createLocalGateway(AgentRegistry agentRegistry) {
        // 返回 null，控制器会处理
        return null;
    }
    
    /**
     * CORS 配置
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOriginPatterns(Arrays.asList("*"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        
        return new CorsFilter(source);
    }
    
    /**
     * 静态资源配置 - 支持访问前端页面
     */
    @Bean
    public WebMvcConfigurer staticResourcesConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/**")
                    .addResourceLocations("classpath:/public/", "classpath:/static/");
            }
        };
    }

    /**
     * 优雅关闭钩子
     * 关闭顺序：网关 → 动态配置 → Agent 注册表 → RAG → 工厂 → Redis
     */
    @Bean
    public DisposableBean shutdownHook(
            @Nullable DistributedAgentGateway gateway,
            DynamicConfigManager dynamicConfigManager,
            AgentRegistry agentRegistry,
            AgentFactory agentFactory,
            @Nullable RagService ragService,
            @Nullable RedisClient redisClient) {
        return () -> {
            log.info("正在优雅关闭...");

            // 1. 关闭网关（停止接受新请求，等待在途请求完成）
            if (gateway != null) {
                gateway.shutdown();
                log.info("    ✓ 网关已关闭");
            }

            // 2. 停止动态配置（关闭 etcd 监听）
            dynamicConfigManager.stop();
            log.info("    ✓ 动态配置管理器已停止");

            // 3. 清空 Agent 注册表（触发 Agent.close() 和 MCP 连接断开）
            agentRegistry.clear();
            log.info("    ✓ Agent 注册表已清空");

            // 4. 关闭 RAG 服务（Milvus 连接）
            if (ragService != null) {
                ragService.close();
                log.info("    ✓ RAG 服务已关闭");
            }

            // 5. 关闭工厂（清理 MCP 连接池等）
            agentFactory.shutdownMcpClient();
            log.info("    ✓ Agent 工厂已关闭");

            // 6. 关闭 Redis 连接池
            if (redisClient != null) {
                redisClient.close();
                log.info("    ✓ Redis 已关闭");
            }

            log.info("优雅关闭完成");
        };
    }
    
    /**
     * 解析端口参数
     */
    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) || "-p".equals(args[i])) {
                if (i + 1 < args.length) {
                    return Integer.parseInt(args[i + 1]);
                }
            }
        }
        return DEFAULT_PORT;
    }
    
    /**
     * 解析 Redis 配置
     * 直接从顶级 redis 配置块读取
     */
    private static RedisConfig parseRedisConfig() {
        Map<String, Object> redisMap = config.getRedis();
        if (redisMap == null) {
            return new RedisConfig();
        }
        return RedisConfig.fromMap(redisMap);
    }
    
    /**
     * 创建网关配置
     */
    @SuppressWarnings("unchecked")
    private static GatewayConfig createGatewayConfig() {
        GatewayConfig gatewayConfig = new GatewayConfig();

        Map<String, Object> gatewayMap = config.getGateway();
        if (gatewayMap != null) {
            if (gatewayMap.get("globalQps") != null) {
                gatewayConfig.setGlobalQps(((Number) gatewayMap.get("globalQps")).intValue());
            }
            if (gatewayMap.get("userQps") != null) {
                gatewayConfig.setUserQps(((Number) gatewayMap.get("userQps")).intValue());
            }
            if (gatewayMap.get("globalMaxConcurrent") != null) {
                gatewayConfig.setGlobalMaxConcurrent(((Number) gatewayMap.get("globalMaxConcurrent")).intValue());
            }
            if (gatewayMap.get("rateLimitEnabled") != null) {
                gatewayConfig.setRateLimitEnabled((Boolean) gatewayMap.get("rateLimitEnabled"));
            }

            // 读取认证配置
            Map<String, Object> authMap = (Map<String, Object>) gatewayMap.get("auth");
            if (authMap != null) {
                if (authMap.get("enabled") != null) {
                    gatewayConfig.setAuthEnabled((Boolean) authMap.get("enabled"));
                }
                Map<String, String> apiKeys = (Map<String, String>) authMap.get("api-keys");
                if (apiKeys != null) {
                    gatewayConfig.getApiKeys().putAll(apiKeys);
                }
            }

            // 读取安全配置
            Map<String, Object> securityMap = (Map<String, Object>) gatewayMap.get("security");
            if (securityMap != null) {
                if (securityMap.get("enabled") != null) {
                    gatewayConfig.setSecurityEnabled((Boolean) securityMap.get("enabled"));
                }
            }
        }

        return gatewayConfig;
    }
}