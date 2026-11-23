package com.bitguide.stage4;

import com.bitguide.agent.AgentFactory;
import com.bitguide.agent.AgentRegistry;
import com.bitguide.config.BitGuideConfig;
import com.bitguide.config.ConfigLoader;
import com.bitguide.config.RedisConfig;
import com.bitguide.config.dynamic.DynamicConfigManager;
import com.bitguide.config.dynamic.EtcdConfig;
import com.bitguide.config.dynamic.EtcdConfigWatcher;
import com.bitguide.llm.LLMGateway;
import com.bitguide.memory.ConversationMemory;
import com.bitguide.rag.RagService;
import com.bitguide.redis.RedisClient;
import com.bitguide.stage4.cli.WorkflowCli;
import com.bitguide.stage4.config.WorkflowRegistry;
import com.bitguide.workflow.checkpoint.CheckpointStore;
import com.bitguide.workflow.checkpoint.MemoryCheckpointStore;
import com.bitguide.workflow.checkpoint.RedisCheckpointStore;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Properties;

@SpringBootApplication(scanBasePackages = {
    "com.bitguide.stage4",
    "com.bitguide.agent",
    "com.bitguide.tool",
    "com.bitguide.mcp",
    "com.bitguide.skill",
    "com.bitguide.rag",
    "com.bitguide.memory",
    "com.bitguide.config",
    "com.bitguide.llm",
    "com.bitguide.gateway",
    "com.bitguide.redis"
})
public class Stage4App {

    private static final Logger log = LoggerFactory.getLogger(Stage4App.class);
    private static BitGuideConfig config;
    private static RedisConfig redisConfig;

    static boolean isCliMode(String[] args) {
        for (String arg : args) {
            if ("--server".equals(arg)) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        boolean cliMode = isCliMode(args);

        config = ConfigLoader.load("src/stage4/java/com/bitguide/stage4/config/stage4.yml");
        redisConfig = parseRedisConfig();

        SpringApplication app = new SpringApplication(Stage4App.class);
        Properties defaults = new Properties();
        defaults.setProperty("server.port", "8081");

        if (cliMode) {
            defaults.setProperty("spring.main.web-application-type", "none");
            log.info("BitGuide Stage 4 starting in CLI mode");
        }

        app.setDefaultProperties(defaults);
        app.run(args);

        if (!cliMode) {
            log.info("BitGuide Stage 4 (Workflow Engine) started on port 8081");
        }
    }

    // ---- 基础设施 Bean ----

    @Bean
    @Nullable
    public RedisClient redisClient() {
        if (redisConfig.isEnabled()) {
            RedisClient client = new RedisClient(redisConfig);
            if (client.isHealthy()) {
                log.info("    ✓ Redis 连接成功");
                return client;
            }
        }
        return null;
    }

    @Bean
    public LLMGateway llmGateway() {
        log.info("    ✓ LLM 网关初始化完成");
        return new LLMGateway();
    }

    @Bean
    @Nullable
    public RagService ragService() {
        if (config.getRag() != null && config.getRag().isEnabled()) {
            try {
                RagService service = new RagService(config.getRag());
                log.info("    ✓ RAG 服务初始化完成");
                return service;
            } catch (Exception e) {
                log.warn("    ✗ RAG 初始化失败: {}", e.getMessage());
            }
        }
        return null;
    }

    @Bean
    public ConversationMemory conversationMemory() {
        log.info("    ✓ 本地会话存储初始化完成");
        return new ConversationMemory();
    }

    @Bean
    public EtcdConfig etcdConfig() {
        EtcdConfig ec = config.getEtcd();
        return ec != null ? ec : new EtcdConfig();
    }

    @Bean
    @Nullable
    public EtcdConfigWatcher etcdConfigWatcher(EtcdConfig etcdConfig) {
        if (etcdConfig.isEnabled()) {
            return new EtcdConfigWatcher(etcdConfig);
        }
        return null;
    }

    // ---- Agent Bean ----

    @Bean
    public AgentFactory agentFactory(LLMGateway llmGateway,
                                      @Nullable RagService ragService,
                                      ConversationMemory conversationMemory) {
        log.info("    ✓ AgentFactory 初始化...");
        return new AgentFactory(config, llmGateway, ragService, conversationMemory);
    }

    @Bean
    public AgentRegistry agentRegistry(AgentFactory agentFactory) {
        AgentRegistry registry = new AgentRegistry(agentFactory);
        // 注册 YAML 中的静态 Agent（etcd 关闭时仍可用）
        if (config.getAgents() != null) {
            for (Map.Entry<String, com.bitguide.config.AgentConfig> entry : config.getAgents().entrySet()) {
                registry.register(entry.getKey(), entry.getValue());
                log.info("    ✓ 已注册 Agent: {}", entry.getKey());
            }
        }
        return registry;
    }

    @Bean
    public DynamicConfigManager dynamicConfigManager(
            @Nullable EtcdConfigWatcher etcdConfigWatcher,
            AgentRegistry agentRegistry,
            AgentFactory agentFactory) {
        DynamicConfigManager manager = new DynamicConfigManager(etcdConfigWatcher, agentRegistry, agentFactory);
        if (etcdConfigWatcher != null) {
            if (agentFactory.getSkillManager() != null) {
                manager.setSkillManager(agentFactory.getSkillManager());
            }
            manager.init();
            log.info("    ✓ 动态配置管理器初始化完成");
            log.info("    - 可用 Agent: {}", agentRegistry.getAvailableAgentIds());
        }
        return manager;
    }

    // ---- Stage 4 工作流 Bean ----

    @Bean
    public WorkflowRegistry workflowRegistry(AgentRegistry agentRegistry,
                                             org.springframework.context.ApplicationContext ctx) {
        CheckpointStore store = null;
        try {
            JedisPool jedisPool = ctx.getBean(JedisPool.class);
            store = new RedisCheckpointStore(jedisPool);
            log.info("    ✓ Workflow checkpoint: Redis");
        } catch (Exception e) {
            store = new MemoryCheckpointStore();
            log.info("    - Workflow checkpoint: Memory（Redis 未连接，断点续跑不跨重启）");
        }
        return new WorkflowRegistry(agentRegistry, store);
    }

    @Bean
    public CommandLineRunner cliLauncher(WorkflowRegistry workflowRegistry) {
        return args -> {
            if (isCliMode(args)) {
                WorkflowCli cli = new WorkflowCli(workflowRegistry);
                cli.start();
                System.exit(0);
            }
        };
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**").allowedOrigins("*");
            }
        };
    }

    private static RedisConfig parseRedisConfig() {
        Map<String, Object> redisMap = config.getRedis();
        if (redisMap == null) return new RedisConfig();
        return RedisConfig.fromMap(redisMap);
    }
}
