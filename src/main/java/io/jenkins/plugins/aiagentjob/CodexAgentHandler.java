package io.jenkins.plugins.aiagentjob;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.TaskListener;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CodexAgentHandler extends AiAgentTypeHandler {
    private boolean customConfigEnabled;
    private String customConfigToml = "";

    @DataBoundConstructor
    public CodexAgentHandler() {}

    @Override
    public String getId() {
        return "CODEX";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "OPENAI_API_KEY";
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command = new ArrayList<>();
        command.add("codex");
        command.add("exec");
        command.add("--json");
        command.add("--skip-git-repo-check");
        if (config.isYoloMode()) {
            command.add("--dangerously-bypass-approvals-and-sandbox");
        } else {
            command.add("--sandbox");
            command.add("workspace-write");
            command.add("--full-auto");
        }
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
            AiAgentConfiguration config, FilePath workspace, TaskListener listener)
            throws IOException, InterruptedException {
        AiAgentExecutionCustomization customization = AiAgentExecutionCustomization.empty();
        if (!customConfigEnabled) {
            return customization;
        }
        FilePath tempDir = AiAgentTempFiles.tempRoot(workspace);
        FilePath homeDir = tempDir.child("ai-agent-codex-home-" + System.nanoTime());
        FilePath codexDir = homeDir.child(".codex");
        codexDir.mkdirs();
        codexDir.child("config.toml").write(Util.fixNull(customConfigToml), "UTF-8");
        String codexHome = homeDir.getRemote();
        customization.putEnvironment("HOME", codexHome);
        customization.putEnvironment("USERPROFILE", codexHome);
        customization.addCleanupAction(homeDir::deleteRecursive);
        listener.getLogger()
                .println("[ai-agent] Using job-scoped Codex config.toml from agent configuration.");
        return customization;
    }

    public boolean isCustomConfigEnabled() {
        return customConfigEnabled;
    }

    @DataBoundSetter
    public void setCustomConfigEnabled(boolean customConfigEnabled) {
        this.customConfigEnabled = customConfigEnabled;
    }

    public String getCustomConfigToml() {
        return customConfigToml;
    }

    @DataBoundSetter
    public void setCustomConfigToml(String customConfigToml) {
        this.customConfigToml = Util.fixNull(customConfigToml);
    }

    @Extension
    @Symbol("codex")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Codex CLI";
        }
    }
}
