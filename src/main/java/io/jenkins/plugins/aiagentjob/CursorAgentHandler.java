package io.jenkins.plugins.aiagentjob;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;

public final class CursorAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public CursorAgentHandler() {}

    @Override
    public String getId() {
        return "CURSOR_AGENT";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "CURSOR_API_KEY";
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("agent");
        command.add("-p");
        command.add("--output-format=stream-json");
        command.add("--trust");
        command.add("--approve-mcps");
        if (config.isYoloMode()) {
            command.add("--yolo");
        }
        String model = Util.fixEmptyAndTrim(config.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        command.add(prompt);
        return command;
    }

    @Extension
    @Symbol("cursor")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Cursor Agent";
        }
    }
}
