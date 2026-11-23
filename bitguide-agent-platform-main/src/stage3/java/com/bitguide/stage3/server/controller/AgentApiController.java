package com.bitguide.stage3.server.controller;

import com.bitguide.agent.AgentRegistry;
import com.bitguide.gateway.distributed.DistributedAgentGateway;
import com.bitguide.gateway.model.GatewayRequest;
import com.bitguide.gateway.model.GatewayResponse;
import com.bitguide.memory.ConversationMemory;
import com.bitguide.stage3.config.ChatMetrics;
import com.bitguide.stage3.server.dto.ApiResponse;
import com.bitguide.stage3.server.dto.ChatRequest;
import com.bitguide.stage3.server.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.*;

/**
 * Agent API 控制器 (Spring Boot)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class AgentApiController {
    
    private final DistributedAgentGateway gateway;
    private final AgentRegistry agentRegistry;
    private final ConversationMemory conversationMemory;
    private final ChatMetrics chatMetrics;

    public AgentApiController(@Nullable DistributedAgentGateway gateway,
                              AgentRegistry agentRegistry,
                              ConversationMemory conversationMemory,
                              @Nullable ChatMetrics chatMetrics) {
        this.gateway = gateway;
        this.agentRegistry = agentRegistry;
        this.conversationMemory = conversationMemory;
        this.chatMetrics = chatMetrics;
    }
    
    /**
     * 聊天接口
     * POST /api/v1/chat
     */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@RequestBody ChatRequest request,
                                          HttpServletRequest httpRequest) {
        long startTime = System.currentTimeMillis();
        
        try {
            // 检查网关是否可用
            if (gateway == null) {
                return ApiResponse.serverError("服务未就绪：分布式网关未初始化，请检查 Redis 配置");
            }
            
            // 参数校验
            if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
                return ApiResponse.badRequest("消息不能为空");
            }
            
            if (request.getAgentId() == null || request.getAgentId().trim().isEmpty()) {
                return ApiResponse.badRequest("agentId 不能为空");
            }
            
            // 生成或使用 sessionId
            String sessionId = request.getSessionId();
            if (sessionId == null || sessionId.trim().isEmpty()) {
                sessionId = "session-" + UUID.randomUUID().toString().substring(0, 8);
            }
            
            // 提取认证 Token（支持 X-API-Key Header 和 Authorization Bearer 两种方式）
            // 前端未携带 token 时使用默认 demo token，保证 Web 界面可直接使用
            String token = httpRequest.getHeader("X-API-Key");
            if (token == null || token.isEmpty()) {
                String authHeader = httpRequest.getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    token = authHeader.substring(7);
                }
            }
            if (token == null || token.isEmpty()) {
                token = "demo-token-001";
            }

            // 构建网关请求
            GatewayRequest gatewayRequest = GatewayRequest.builder()
                    .sessionId(sessionId)
                    .userId(request.getUserId() != null ? request.getUserId() : "anonymous")
                    .agentId(request.getAgentId())
                    .message(request.getMessage())
                    .token(token)
                    .build();
            
            // 调用网关处理
            GatewayResponse gatewayResponse = gateway.process(gatewayRequest);
            
            // 构建响应
            long duration = System.currentTimeMillis() - startTime;
            ChatResponse chatResponse;
            
            if (gatewayResponse.isSuccess()) {
                // 记录成功指标
                if (chatMetrics != null) {
                    chatMetrics.recordChatSuccess(request.getAgentId(), duration);
                }

                chatResponse = ChatResponse.success(
                        sessionId,
                        request.getAgentId(),
                        gatewayResponse.getContent(),
                        duration
                );
                return ApiResponse.success(chatResponse);
            } else {
                // 记录失败指标（限流拒绝单独计数）
                if (chatMetrics != null) {
                    if ("TOO_MANY_REQUESTS".equals(gatewayResponse.getErrorCode())) {
                        chatMetrics.recordRateLimitRejected();
                    }
                    chatMetrics.recordChatError(duration);
                }

                chatResponse = ChatResponse.error(
                        sessionId,
                        request.getAgentId(),
                        gatewayResponse.getErrorCode(),
                        gatewayResponse.getErrorMessage()
                );
                return ApiResponse.error(500, gatewayResponse.getErrorMessage(), chatResponse);
            }
            
        } catch (Exception e) {
            log.error("聊天处理失败", e);
            return ApiResponse.serverError("处理失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取可用的 Agent 列表
     * GET /api/v1/agents
     */
    @GetMapping("/agents")
    public ApiResponse<List<Map<String, Object>>> listAgents() {
        try {
            Map<String, AgentRegistry.AgentInfo> agents = agentRegistry.getAllAgentInfo();
            
            List<Map<String, Object>> agentList = new ArrayList<>();
            for (Map.Entry<String, AgentRegistry.AgentInfo> entry : agents.entrySet()) {
                Map<String, Object> agentInfo = new HashMap<>();
                agentInfo.put("id", entry.getKey());
                agentInfo.put("name", entry.getValue().name());
                agentInfo.put("description", entry.getValue().description());
                agentList.add(agentInfo);
            }
            
            return ApiResponse.success(agentList);
        } catch (Exception e) {
            log.error("获取Agent列表失败", e);
            return ApiResponse.serverError("获取Agent列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取 Agent 详情
     * GET /api/v1/agents/{agentId}
     */
    @GetMapping("/agents/{agentId}")
    public ApiResponse<Map<String, Object>> getAgent(@PathVariable("agentId") String agentId) {
        try {
            Map<String, AgentRegistry.AgentInfo> allInfo = agentRegistry.getAllAgentInfo();
            AgentRegistry.AgentInfo info = allInfo.get(agentId);
            
            if (info == null) {
                return ApiResponse.error(404, "Agent 不存在: " + agentId);
            }
            
            Map<String, Object> agentInfo = new HashMap<>();
            agentInfo.put("id", agentId);
            agentInfo.put("name", info.name());
            agentInfo.put("description", info.description());
            
            return ApiResponse.success(agentInfo);
        } catch (Exception e) {
            log.error("获取Agent详情失败", e);
            return ApiResponse.serverError("获取Agent详情失败: " + e.getMessage());
        }
    }
    
    /**
     * 切换 Agent（清除会话历史，切换到新 Agent）
     * POST /api/v1/sessions/{sessionId}/switch
     */
    @PostMapping("/sessions/{sessionId}/switch")
    public ApiResponse<Map<String, Object>> switchAgent(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> request) {
        try {
            if (gateway == null) {
                return ApiResponse.serverError("服务未就绪：分布式网关未初始化");
            }
            
            String newAgentId = request.get("agentId");
            if (newAgentId == null || newAgentId.trim().isEmpty()) {
                return ApiResponse.badRequest("agentId 不能为空");
            }
            
            // 验证 Agent 是否存在
            Map<String, AgentRegistry.AgentInfo> allInfo = agentRegistry.getAllAgentInfo();
            if (!allInfo.containsKey(newAgentId)) {
                return ApiResponse.error(404, "Agent 不存在: " + newAgentId);
            }
            
            // 清除当前会话的分布式状态（Lane、限流计数等）
            // 注意：本地会话历史按 sessionId:agentId 隔离，切换时不需要清除
            gateway.clearSession(sessionId);
            
            // 返回切换结果
            Map<String, Object> result = new HashMap<>();
            result.put("sessionId", sessionId);
            result.put("agentId", newAgentId);
            result.put("agentName", allInfo.get(newAgentId).name());
            result.put("message", "已切换到 Agent: " + allInfo.get(newAgentId).name());
            
            log.info("Agent 切换成功: sessionId={}, newAgentId={}", sessionId, newAgentId);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("切换 Agent 失败", e);
            return ApiResponse.serverError("切换 Agent 失败: " + e.getMessage());
        }
    }
    
    /**
     * 清除会话
     * DELETE /api/v1/sessions/{sessionId}
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> clearSession(@PathVariable("sessionId") String sessionId) {
        try {
            if (gateway == null) {
                return ApiResponse.serverError("服务未就绪：分布式网关未初始化");
            }
            gateway.clearSession(sessionId);
            // 清除该 session 下所有 Agent 的本地会话历史
            conversationMemory.clearSessionByPrefix(sessionId);
            return ApiResponse.success(null, "会话已清除");
        } catch (Exception e) {
            log.error("清除会话失败", e);
            return ApiResponse.serverError("清除会话失败: " + e.getMessage());
        }
    }
}