package com.bitguide.workflow.checkpoint;

import java.time.Instant;
import java.util.Map;

public class Checkpoint {
    private String executionId;
    private String currentNodeId;
    private Map<String, Object> stateData;
    private Instant createdAt;
    private String status; // running, paused, completed, failed

    public Checkpoint() {
        this.createdAt = Instant.now();
    }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }

    public Map<String, Object> getStateData() { return stateData; }
    public void setStateData(Map<String, Object> stateData) { this.stateData = stateData; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
