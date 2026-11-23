package com.bitguide.workflow.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared state (blackboard) that flows through all nodes in a workflow.
 * Each key can have an associated Channel that controls merge semantics.
 */
public class WorkflowState {

    private final Map<String, Object> data;
    private final Map<String, Channel> channels;
    private String executionId;
    private String currentNodeId;

    public WorkflowState() {
        this.data = new HashMap<>();
        this.channels = new HashMap<>();
    }

    public WorkflowState(Map<String, Channel> channels) {
        this.data = new HashMap<>();
        this.channels = channels != null ? new HashMap<>(channels) : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) data.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object val = data.get(key);
        return val != null ? (T) val : defaultValue;
    }

    public void set(String key, Object value) {
        Channel channel = channels.get(key);
        if (channel != null) {
            data.put(key, channel.merge(data.get(key), value));
        } else {
            data.put(key, value);
        }
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public void remove(String key) {
        data.remove(key);
    }

    public Map<String, Object> toMap() {
        return new HashMap<>(data);
    }

    public WorkflowState copy() {
        WorkflowState copy = new WorkflowState(this.channels);
        copy.data.putAll(this.data);
        copy.executionId = this.executionId;
        copy.currentNodeId = this.currentNodeId;
        return copy;
    }

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }

    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
}
