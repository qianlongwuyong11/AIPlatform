package com.bitguide.gateway.router;

import com.bitguide.agent.AgentRegistry;
import com.bitguide.gateway.model.GatewayRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 领域路由器
 * 根据请求中的 agentId 或 domain 路由到对应的 Agent
 */
@Slf4j
public class DomainRouter {
    
    private final AgentRegistry agentRegistry;
    
    /**
     * domain 到 agentId 的映射
     * 可以通过配置或动态注册来维护
     */
    private final Map<String, String> domainMapping = new HashMap<>();
    
    public DomainRouter(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
        
        // 初始化一些默认的domain映射
        initDefaultMappings();
    }
    
    /**
     * 初始化默认的 domain 映射
     */
    private void initDefaultMappings() {
        // 可以根据实际Agent配置来设置映射
        domainMapping.put("parenting", "parenting_advisor");
        domainMapping.put("stock", "stock_assistant");
        domainMapping.put("finance", "stock_assistant");
    }
    
    /**
     * 路由请求到对应的 Agent
     * 
     * @param request 网关请求
     * @return 路由结果
     */
    public RoutingResult route(GatewayRequest request) {
        // 1. 优先使用 agentId
        if (request.getAgentId() != null && !request.getAgentId().isEmpty()) {
            return routeByAgentId(request.getAgentId());
        }
        
        // 2. 其次使用 domain
        if (request.getDomain() != null && !request.getDomain().isEmpty()) {
            return routeByDomain(request.getDomain());
        }
        
        // 3. 都没有，返回错误
        log.warn("请求缺少路由信息: sessionId={}", request.getSessionId());
        return RoutingResult.failure("agentId or domain is required");
    }
    
    /**
     * 根据 agentId 路由
     */
    private RoutingResult routeByAgentId(String agentId) {
        // 检查 Agent 是否存在
        if (agentRegistry.exists(agentId)) {
            log.debug("路由成功: agentId={}", agentId);
            return RoutingResult.success(agentId, "agentId");
        }
        
        log.warn("Agent不存在: agentId={}", agentId);
        return RoutingResult.notFound(agentId);
    }
    
    /**
     * 根据 domain 路由
     */
    private RoutingResult routeByDomain(String domain) {
        // 查找 domain 对应的 agentId
        String agentId = domainMapping.get(domain.toLowerCase());
        
        if (agentId == null) {
            log.warn("Domain未注册: domain={}", domain);
            return RoutingResult.failure("Unknown domain: " + domain);
        }
        
        // 检查 Agent 是否存在
        if (agentRegistry.exists(agentId)) {
            log.debug("路由成功: domain={} -> agentId={}", domain, agentId);
            return RoutingResult.success(agentId, "domain");
        }
        
        log.warn("Domain映射的Agent不存在: domain={}, agentId={}", domain, agentId);
        return RoutingResult.notFound(agentId);
    }
    
    /**
     * 注册 domain 映射
     * 
     * @param domain 领域标识
     * @param agentId Agent ID
     */
    public void registerDomain(String domain, String agentId) {
        domainMapping.put(domain.toLowerCase(), agentId);
        log.info("注册domain映射: {} -> {}", domain, agentId);
    }
    
    /**
     * 移除 domain 映射
     * 
     * @param domain 领域标识
     */
    public void unregisterDomain(String domain) {
        domainMapping.remove(domain.toLowerCase());
        log.info("移除domain映射: {}", domain);
    }
    
    /**
     * 获取所有可用的 domain
     */
    public Map<String, String> getAvailableDomains() {
        return new HashMap<>(domainMapping);
    }
}