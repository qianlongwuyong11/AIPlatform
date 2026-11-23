package com.bitguide.stage4.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunResponse {
    private String executionId;
    private String status;
    private Map<String, Object> result;
    private Long durationMs;
}
