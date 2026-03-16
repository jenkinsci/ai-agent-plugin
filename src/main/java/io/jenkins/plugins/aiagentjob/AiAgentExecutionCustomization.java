package io.jenkins.plugins.aiagentjob;

import hudson.model.TaskListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-execution customizations contributed by an {@link AiAgentTypeHandler}, including extra
 * environment variables and cleanup hooks.
 */
public final class AiAgentExecutionCustomization {
    @FunctionalInterface
    public interface CleanupAction {
        void run() throws IOException, InterruptedException;
    }

    private final Map<String, String> environment = new LinkedHashMap<>();
    private final List<CleanupAction> cleanupActions = new ArrayList<>();

    public static AiAgentExecutionCustomization empty() {
        return new AiAgentExecutionCustomization();
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void putEnvironment(String key, String value) {
        environment.put(key, value);
    }

    public void addCleanupAction(CleanupAction cleanupAction) {
        cleanupActions.add(cleanupAction);
    }

    public void cleanup(TaskListener listener) {
        for (CleanupAction cleanupAction : cleanupActions) {
            try {
                cleanupAction.run();
            } catch (IOException | InterruptedException e) {
                listener.getLogger()
                        .println("[ai-agent] Warning: cleanup failed: " + e.getMessage());
            }
        }
    }
}
