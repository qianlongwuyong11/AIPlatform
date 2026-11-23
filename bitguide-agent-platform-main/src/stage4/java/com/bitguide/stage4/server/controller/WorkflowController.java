package com.bitguide.stage4.server.controller;

import com.bitguide.stage4.config.WorkflowRegistry;
import com.bitguide.stage4.server.dto.*;
import com.bitguide.workflow.checkpoint.Checkpoint;
import com.bitguide.workflow.checkpoint.CheckpointStore;
import com.bitguide.workflow.core.WorkflowExecutor;
import com.bitguide.workflow.core.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowRegistry workflowRegistry;

    public WorkflowController(WorkflowRegistry workflowRegistry) {
        this.workflowRegistry = workflowRegistry;
    }

    /**
     * List all available workflows.
     */
    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listWorkflows() {
        Set<String> workflows = workflowRegistry.getAvailableWorkflows();
        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", workflows,
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Run a workflow synchronously.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runWorkflow(@RequestBody WorkflowRunRequest request) {
        if (request.getWorkflowId() == null || request.getWorkflowId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", 400, "message", "workflowId is required"
            ));
        }

        long start = System.currentTimeMillis();
        try {
            WorkflowExecutor executor = workflowRegistry.createExecutor(request.getWorkflowId());

            WorkflowState initialState = new WorkflowState();
            if (request.getInput() != null) {
                for (Map.Entry<String, Object> entry : request.getInput().entrySet()) {
                    initialState.set(entry.getKey(), entry.getValue());
                }
            }

            WorkflowState result = executor.run(initialState, request.getWorkflowId());
            long duration = System.currentTimeMillis() - start;

            return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                    "executionId", result.getExecutionId(),
                    "status", "completed",
                    "result", result.toMap(),
                    "durationMs", duration
                ),
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Workflow execution failed: {}", request.getWorkflowId(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", 500,
                "message", "Workflow execution failed: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Get workflow execution status.
     */
    @GetMapping("/status/{executionId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String executionId) {
        CheckpointStore store = workflowRegistry.getCheckpointStore();
        if (store == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "code", 400, "message", "Checkpoint not configured"
            ));
        }

        Checkpoint cp = store.load(executionId);
        if (cp == null) {
            return ResponseEntity.status(404).body(Map.of(
                "code", 404, "message", "Execution not found: " + executionId
            ));
        }

        return ResponseEntity.ok(Map.of(
            "code", 200,
            "data", Map.of(
                "executionId", cp.getExecutionId(),
                "currentNodeId", cp.getCurrentNodeId() != null ? cp.getCurrentNodeId() : "",
                "status", cp.getStatus(),
                "stateData", cp.getStateData() != null ? cp.getStateData() : Map.of(),
                "createdAt", cp.getCreatedAt() != null ? cp.getCreatedAt().toString() : ""
            ),
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Resume a paused workflow with user input.
     */
    @PostMapping("/resume/{executionId}")
    public ResponseEntity<Map<String, Object>> resumeWorkflow(
            @PathVariable String executionId,
            @RequestBody WorkflowResumeRequest request) {

        long start = System.currentTimeMillis();
        try {
            // Find the workflow for this execution (simplified: try all)
            // In a real system, executionId would encode the workflowId
            CheckpointStore store = workflowRegistry.getCheckpointStore();
            if (store == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "code", 400, "message", "Checkpoint not configured"
                ));
            }

            Checkpoint cp = store.load(executionId);
            if (cp == null) {
                return ResponseEntity.status(404).body(Map.of(
                    "code", 404, "message", "Execution not found"
                ));
            }

            // For now, we need to know which workflow to resume
            // Store the workflowId in checkpoint state data
            String workflowId = (String) cp.getStateData().get("_workflow_id");
            if (workflowId == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "code", 400, "message", "Cannot determine workflow ID from checkpoint"
                ));
            }

            WorkflowExecutor executor = workflowRegistry.createExecutor(workflowId);
            WorkflowState result = executor.resume(executionId, request.getInput());
            long duration = System.currentTimeMillis() - start;

            return ResponseEntity.ok(Map.of(
                "code", 200,
                "data", Map.of(
                    "executionId", result.getExecutionId(),
                    "status", "completed",
                    "result", result.toMap(),
                    "durationMs", duration
                ),
                "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.error("Workflow resume failed: {}", executionId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                "code", 500,
                "message", "Resume failed: " + e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }
}
