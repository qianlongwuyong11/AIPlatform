package com.bitguide.stage3.server.controller;

import com.bitguide.gateway.distributed.DistributedAgentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器
 */
@Slf4j
@RestController
public class HealthController {
    
    private final DistributedAgentGateway gateway;
    
    @Autowired(required = false)
    public HealthController(@Nullable DistributedAgentGateway gateway) {
        this.gateway = gateway;
    }
    
    /**
     * 健康检查
     * GET /health
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        if (gateway != null) {
            health.put("activeSessions", gateway.getActiveSessionCount());
            health.put("activeLanes", gateway.getActiveLaneCount());
        }
        return health;
    }
}