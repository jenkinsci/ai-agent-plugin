package io.jenkins.plugins.aiagentjob;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;

public final class ClaudeCodeAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public ClaudeCodeAgentHandler() {}

    @Override
    public String getId() {
        return "CLAUDE_CODE";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "ANTHROPIC_API_KEY";
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("npx");
        command.add("-y");
        command.add("@anthropic-ai/claude-code");
        command.add("-p");
        command.add(prompt);
        command.add("--output-format=stream-json");
        command.add("--verbose");
        if (config.isYoloMode()) {
            command.add("--dangerously-skip-permissions");
        } else if (config.isRequireApprovals()) {
            command.add("--permission-mode=default");
        }
        String model = Util.fixEmptyAndTrim(config.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        return command;
    }

    @Extension
    @Symbol("claudeCode")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Claude Code";
        }
    }
}
