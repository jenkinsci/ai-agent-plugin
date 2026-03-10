package io.jenkins.plugins.aiagentjob;

import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.model.AbstractDescribableImpl;
import hudson.model.TaskListener;

import java.io.IOException;
import java.util.List;

/**
 * Describable extension point for an AI agent implementation.
 *
 * <p>Implementations define their default command, default API key env var, and can contribute
 * agent-specific environment setup/cleanup.
 */
public abstract class AiAgentTypeHandler extends AbstractDescribableImpl<AiAgentTypeHandler>
        implements ExtensionPoint {
    /** Stable identifier for this agent implementation. */
    public abstract String getId();

    public abstract String getDefaultApiKeyEnvVar();

    public abstract List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt);

    public AiAgentExecutionCustomization prepareExecution(
            AiAgentConfiguration config, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        return AiAgentExecutionCustomization.empty();
    }
}
