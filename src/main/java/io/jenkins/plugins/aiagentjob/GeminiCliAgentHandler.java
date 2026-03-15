package io.jenkins.plugins.aiagentjob;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;

public final class GeminiCliAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public GeminiCliAgentHandler() {}

    @Override
    public String getId() {
        return "GEMINI_CLI";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "GEMINI_API_KEY";
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("gemini");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format");
        command.add("stream-json");
        if (config.isYoloMode()) {
            command.add("--yolo");
        } else if (config.isRequireApprovals()) {
            command.add("--approval-mode");
            command.add("default");
        }
        String model = Util.fixEmptyAndTrim(config.getModel());
        if (model != null) {
            command.add("-m");
            command.add(model);
        }
        return command;
    }

    @Extension
    @Symbol("geminiCli")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Gemini CLI";
        }
    }
}
