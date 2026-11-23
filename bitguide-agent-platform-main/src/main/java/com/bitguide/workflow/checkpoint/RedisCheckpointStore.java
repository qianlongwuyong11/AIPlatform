package com.bitguide.workflow.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;

public class RedisCheckpointStore implements CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointStore.class);
    private static final String KEY_PREFIX = "bitguide:workflow:checkpoint:";
    private static final String INDEX_KEY = "bitguide:workflow:checkpoints";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public RedisCheckpointStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void save(Checkpoint checkpoint) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(checkpoint);
            String key = KEY_PREFIX + checkpoint.getExecutionId();
            jedis.set(key, json);
            // Index by status for listByStatus queries
            jedis.hset(INDEX_KEY, checkpoint.getExecutionId(), checkpoint.getStatus());
            log.debug("Saved checkpoint: {} status={}", checkpoint.getExecutionId(), checkpoint.getStatus());
        } catch (Exception e) {
            log.error("Failed to save checkpoint: {}", checkpoint.getExecutionId(), e);
            throw new RuntimeException("Failed to save checkpoint", e);
        }
    }

    @Override
    public Checkpoint load(String executionId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(KEY_PREFIX + executionId);
            if (json == null) return null;
            return objectMapper.readValue(json, Checkpoint.class);
        } catch (Exception e) {
            log.error("Failed to load checkpoint: {}", executionId, e);
            throw new RuntimeException("Failed to load checkpoint", e);
        }
    }

    @Override
    public void update(String executionId, String status, Map<String, Object> stateData) {
        Checkpoint cp = load(executionId);
        if (cp == null) {
            throw new IllegalArgumentException("Checkpoint not found: " + executionId);
        }
        cp.setStatus(status);
        if (stateData != null) {
            cp.setStateData(stateData);
        }
        save(cp);
    }

    @Override
    public List<Checkpoint> listByStatus(String status) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> index = jedis.hgetAll(INDEX_KEY);
            List<Checkpoint> result = new ArrayList<>();
            for (Map.Entry<String, String> entry : index.entrySet()) {
                if (status.equals(entry.getValue())) {
                    Checkpoint cp = load(entry.getKey());
                    if (cp != null) result.add(cp);
                }
            }
            return result;
        }
    }
}
