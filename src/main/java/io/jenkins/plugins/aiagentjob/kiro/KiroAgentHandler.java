package io.jenkins.plugins.aiagentjob.kiro;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class KiroAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public KiroAgentHandler() {}

    @Override
    public String getId() {
        return "KIRO_CLI";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "KIRO_API_KEY";
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return Set.of("low", "medium", "high", "xhigh", "max");
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("kiro-cli");
        command.add("chat");
        command.add("--no-interactive");
        command.add("--wrap");
        command.add("never");
        command.add("--trust-all-tools");
        command.add(prompt);
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        String model = Util.fixEmptyAndTrim(selection.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        String reasoningEffort = Util.fixEmptyAndTrim(selection.getReasoningEffort());
        if (reasoningEffort != null) {
            command.add("--effort");
            command.add(reasoningEffort);
        }
        return command;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return KiroLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return KiroStatsExtractor.INSTANCE;
    }

    @Override
    public AiAgentTypeHandler.AcpExecutionSpec buildAcpExecution(AiAgentConfiguration config) {
        List<String> command = new ArrayList<>();
        command.add("kiro-cli");
        command.add("acp");
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        return new AiAgentTypeHandler.AcpExecutionSpec(
                command, selection.getModel(), selection.getReasoningEffort());
    }

    @Extension
    @Symbol("kiro")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Kiro CLI";
        }
    }
}
