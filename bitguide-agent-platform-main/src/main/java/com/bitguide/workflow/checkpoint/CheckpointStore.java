package com.bitguide.workflow.checkpoint;

import java.util.List;
import java.util.Map;

public interface CheckpointStore {
    void save(Checkpoint checkpoint);
    Checkpoint load(String executionId);
    void update(String executionId, String status, Map<String, Object> stateData);
    List<Checkpoint> listByStatus(String status);
}
