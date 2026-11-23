package com.bitguide.stage4.cli;

import com.bitguide.stage4.config.WorkflowRegistry;
import com.bitguide.workflow.core.WorkflowExecutor;
import com.bitguide.workflow.core.WorkflowState;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interactive CLI for running workflows.
 * Students can select a workflow and chat with it in the terminal.
 */
public class WorkflowCli {

    private final WorkflowRegistry registry;
    private final Scanner scanner;
    private final AtomicLong sessionCounter = new AtomicLong(0);

    public WorkflowCli(WorkflowRegistry registry) {
        this.registry = registry;
        this.scanner = new Scanner(System.in);
    }

    public void start() {
        printBanner();

        Set<String> workflows = registry.getAvailableWorkflows();
        if (workflows.isEmpty()) {
            System.out.println("[warn] No workflows available");
            return;
        }

        // Select workflow
        String selectedWorkflow = selectWorkflow(workflows);

        System.out.println();
        System.out.println("[ok] Selected workflow: " + selectedWorkflow);
        System.out.println("Type your question to start (quit to exit, list to see workflows, switch to change)");
        System.out.println("-------------------------------------------------------------------------");

        // Interactive loop
        while (true) {
            System.out.print("\nYou: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if ("quit".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                System.out.println("Goodbye!");
                break;
            }
            if ("list".equalsIgnoreCase(input)) {
                System.out.println("Available workflows: " + workflows);
                continue;
            }
            if ("switch".equalsIgnoreCase(input)) {
                selectedWorkflow = selectWorkflow(workflows);
                System.out.println("[ok] Switched to workflow: " + selectedWorkflow);
                continue;
            }

            // Execute workflow
            try {
                long startTime = System.currentTimeMillis();
                WorkflowExecutor executor = registry.createExecutor(selectedWorkflow);

                WorkflowState initialState = new WorkflowState();
                initialState.set("user_input", input);

                System.out.println("\n[Workflow executing...]");
                WorkflowState result = executor.run(initialState, selectedWorkflow);
                long duration = System.currentTimeMillis() - startTime;

                // Check for interrupt — if paused, prompt user and resume
                while (Boolean.TRUE.equals(result.get("_interrupted"))) {
                    String prompt = result.get("_interrupt_prompt");
                    System.out.println("\n--- 人工确认 ---");
                    if (prompt != null) {
                        System.out.println("Agent 初稿: " + prompt);
                    }
                    System.out.print("请输入补充要求（直接回车确认继续）: ");
                    String feedback = scanner.nextLine().trim();

                    Map<String, Object> userInput = new HashMap<>();
                    if (!feedback.isEmpty()) {
                        userInput.put("user_feedback", feedback);
                    }

                    System.out.println("\n[工作流恢复执行...]");
                    long resumeStart = System.currentTimeMillis();
                    result = executor.resume(result.getExecutionId(), userInput);
                    duration += System.currentTimeMillis() - resumeStart;
                }

                // Print result
                String response = result.get("agent_response");
                if (response != null) {
                    System.out.println("\nAgent: " + response);
                } else {
                    // Try to print the full state for debugging
                    System.out.println("\nWorkflow state:");
                    for (Map.Entry<String, Object> entry : result.toMap().entrySet()) {
                        String key = entry.getKey();
                        if (!key.startsWith("_")) {
                            System.out.println("  " + key + ": " + entry.getValue());
                        }
                    }
                }

                System.out.println("\nTime: " + duration + "ms | Execution ID: " + result.getExecutionId());

            } catch (Exception e) {
                System.out.println("\nExecution failed: " + e.getMessage());
            }
        }
    }

    private String selectWorkflow(Set<String> workflows) {
        List<String> list = new ArrayList<>(workflows);
        if (list.size() == 1) {
            return list.get(0);
        }

        System.out.println();
        System.out.println("Available workflows:");
        for (int i = 0; i < list.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + list.get(i));
        }
        System.out.print("Select (enter number or name): ");

        String choice = scanner.nextLine().trim();

        // Try by number
        try {
            int idx = Integer.parseInt(choice) - 1;
            if (idx >= 0 && idx < list.size()) {
                return list.get(idx);
            }
        } catch (NumberFormatException ignored) {}

        // Try by name
        if (workflows.contains(choice)) {
            return choice;
        }

        // Default to first
        System.out.println("Unrecognized selection, using default: " + list.get(0));
        return list.get(0);
    }

    private void printBanner() {
        System.out.println();
        System.out.println("+=========================================+");
        System.out.println("|   BitGuide Stage 4 - Workflow CLI       |");
        System.out.println("|   Orchestration - Collaboration - State  |");
        System.out.println("+=========================================+");
        System.out.println();
    }
}
