package com.bitguide.workflow.checkpoint;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory checkpoint store for development and testing.
 * Fallback when Redis is not available.
 */
public class MemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentHashMap<String, Checkpoint> store = new ConcurrentHashMap<>();

    @Override
    public void save(Checkpoint checkpoint) {
        store.put(checkpoint.getExecutionId(), checkpoint);
    }

    @Override
    public Checkpoint load(String executionId) {
        return store.get(executionId);
    }

    @Override
    public void update(String executionId, String status, Map<String, Object> stateData) {
        Checkpoint cp = store.get(executionId);
        if (cp != null) {
            cp.setStatus(status);
            cp.setStateData(stateData);
        }
    }

    @Override
    public List<Checkpoint> listByStatus(String status) {
        return store.values().stream()
            .filter(cp -> status.equals(cp.getStatus()))
            .collect(Collectors.toList());
    }
}
