package io.jenkins.plugins.aiagentjob;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;

public final class OpenCodeAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public OpenCodeAgentHandler() {}

    @Override
    public String getId() {
        return "OPENCODE";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "OPENAI_API_KEY";
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("opencode");
        command.add("run");
        command.add("--format");
        command.add("json");
        String model = Util.fixEmptyAndTrim(config.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        command.add(prompt);
        return command;
    }

    @Override
    public AiAgentExecutionCustomization prepareExecution(
            AiAgentConfiguration config, FilePath workspace, TaskListener listener) {
        AiAgentExecutionCustomization customization = AiAgentExecutionCustomization.empty();
        if (config.isYoloMode()) {
            customization.putEnvironment(
                    "OPENCODE_PERMISSION",
                    "{\"edit\":\"allow\",\"bash\":\"allow\",\"webfetch\":\"allow\",\"external_directory\":\"allow\",\"doom_loop\":\"allow\"}");
        } else if (config.isRequireApprovals()) {
            customization.putEnvironment(
                    "OPENCODE_PERMISSION",
                    "{\"edit\":\"ask\",\"bash\":\"ask\",\"webfetch\":\"ask\",\"external_directory\":\"ask\",\"doom_loop\":\"ask\"}");
        }
        return customization;
    }

    @Extension
    @Symbol("openCode")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "OpenCode";
        }
    }
}
