package com.bitguide.stage4.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStatusResponse {
    private String executionId;
    private String currentNodeId;
    private String status;
    private Map<String, Object> stateData;
    private Instant createdAt;
}
