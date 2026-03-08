package io.jenkins.plugins.aiagentjob;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.Collections;

/** Build step that runs AI coding agents and can be used from freestyle and pipeline jobs. */
public class AiAgentBuilder extends Builder implements SimpleBuildStep, AiAgentConfiguration {
    private AgentType agentType = AgentType.CLAUDE_CODE;
    private String model = "";
    private String prompt = "";
    private String workingDirectory = "";
    private boolean yoloMode;
    private boolean requireApprovals;
    private int approvalTimeoutSeconds = 600;
    private String commandOverride = "";
    private String extraArgs = "";
    private String environmentVariables = "";
    private boolean failOnAgentError = true;
    private String setupScript = "";
    private boolean codexCustomConfigEnabled;
    private String codexCustomConfigToml = "";
    private String apiCredentialsId = "";
    private String apiKeyEnvVar = "";

    @DataBoundConstructor
    public AiAgentBuilder() {}

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            throw new IOException("Workspace is not available for this build.");
        }
        return executeForRun(build, workspace, launcher, listener);
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws InterruptedException, IOException {
        executeForRun(run, workspace, launcher, listener);
    }

    private boolean executeForRun(
            Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        AiAgentConfiguration effective = this;
        AiAgentRunAction action = AiAgentRunAction.getOrCreate(run);
        int exitCode =
                AiAgentExecutor.execute(run, workspace, launcher, listener, effective, action);
        if (exitCode != 0 && effective.isFailOnAgentError()) {
            listener.getLogger()
                    .println("[ai-agent] Agent finished with non-zero exit code: " + exitCode);
            if (run instanceof AbstractBuild<?, ?>) {
                return false;
            }
            throw new IOException("AI agent finished with non-zero exit code: " + exitCode);
        }
        if (exitCode != 0) {
            listener.getLogger()
                    .println(
                            "[ai-agent] Agent exited with code "
                                    + exitCode
                                    + ", but failure is disabled.");
        }
        return true;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public AgentType getAgentType() {
        return agentType;
    }

    @DataBoundSetter
    public void setAgentType(AgentType agentType) {
        this.agentType = agentType == null ? AgentType.CLAUDE_CODE : agentType;
    }

    public AgentType[] getAgentTypes() {
        return AgentType.values();
    }

    @Override
    public String getModel() {
        return model;
    }

    @DataBoundSetter
    public void setModel(String model) {
        this.model = Util.fixNull(model);
    }

    @Override
    public String getPrompt() {
        return prompt;
    }

    @DataBoundSetter
    public void setPrompt(String prompt) {
        this.prompt = Util.fixNull(prompt);
    }

    @Override
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    @DataBoundSetter
    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = Util.fixNull(workingDirectory);
    }

    @Override
    public boolean isYoloMode() {
        return yoloMode;
    }

    @DataBoundSetter
    public void setYoloMode(boolean yoloMode) {
        this.yoloMode = yoloMode;
    }

    @Override
    public boolean isRequireApprovals() {
        return requireApprovals;
    }

    @DataBoundSetter
    public void setRequireApprovals(boolean requireApprovals) {
        this.requireApprovals = requireApprovals;
    }

    @Override
    public int getApprovalTimeoutSeconds() {
        return approvalTimeoutSeconds;
    }

    @DataBoundSetter
    public void setApprovalTimeoutSeconds(int approvalTimeoutSeconds) {
        this.approvalTimeoutSeconds = Math.max(1, approvalTimeoutSeconds);
    }

    @Override
    public String getCommandOverride() {
        return commandOverride;
    }

    @DataBoundSetter
    public void setCommandOverride(String commandOverride) {
        this.commandOverride = Util.fixNull(commandOverride);
    }

    @Override
    public String getExtraArgs() {
        return extraArgs;
    }

    @DataBoundSetter
    public void setExtraArgs(String extraArgs) {
        this.extraArgs = Util.fixNull(extraArgs);
    }

    @Override
    public String getEnvironmentVariables() {
        return environmentVariables;
    }

    @DataBoundSetter
    public void setEnvironmentVariables(String environmentVariables) {
        this.environmentVariables = Util.fixNull(environmentVariables);
    }

    @Override
    public boolean isFailOnAgentError() {
        return failOnAgentError;
    }

    @DataBoundSetter
    public void setFailOnAgentError(boolean failOnAgentError) {
        this.failOnAgentError = failOnAgentError;
    }

    @Override
    public String getSetupScript() {
        return setupScript;
    }

    @DataBoundSetter
    public void setSetupScript(String setupScript) {
        this.setupScript = Util.fixNull(setupScript);
    }

    @Override
    public boolean isCodexCustomConfigEnabled() {
        return codexCustomConfigEnabled;
    }

    @DataBoundSetter
    public void setCodexCustomConfigEnabled(boolean codexCustomConfigEnabled) {
        this.codexCustomConfigEnabled = codexCustomConfigEnabled;
    }

    @Override
    public String getCodexCustomConfigToml() {
        return codexCustomConfigToml;
    }

    @DataBoundSetter
    public void setCodexCustomConfigToml(String codexCustomConfigToml) {
        this.codexCustomConfigToml = Util.fixNull(codexCustomConfigToml);
    }

    @Override
    public String getApiCredentialsId() {
        return apiCredentialsId;
    }

    @DataBoundSetter
    public void setApiCredentialsId(String apiCredentialsId) {
        this.apiCredentialsId = Util.fixNull(apiCredentialsId);
    }

    public String getApiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    @DataBoundSetter
    public void setApiKeyEnvVar(String apiKeyEnvVar) {
        this.apiKeyEnvVar = Util.fixNull(apiKeyEnvVar);
    }

    @Override
    public String getEffectiveApiKeyEnvVar() {
        String custom = Util.fixEmptyAndTrim(apiKeyEnvVar);
        return custom != null ? custom : agentType.getDefaultApiKeyEnvVar();
    }

    private Object readResolve() {
        if (agentType == null) {
            agentType = AgentType.CLAUDE_CODE;
        }
        model = Util.fixNull(model);
        prompt = Util.fixNull(prompt);
        workingDirectory = Util.fixNull(workingDirectory);
        commandOverride = Util.fixNull(commandOverride);
        extraArgs = Util.fixNull(extraArgs);
        environmentVariables = Util.fixNull(environmentVariables);
        setupScript = Util.fixNull(setupScript);
        codexCustomConfigToml = Util.fixNull(codexCustomConfigToml);
        apiCredentialsId = Util.fixNull(apiCredentialsId);
        apiKeyEnvVar = Util.fixNull(apiKeyEnvVar);
        approvalTimeoutSeconds = Math.max(1, approvalTimeoutSeconds);
        return this;
    }

    @Extension
    @Symbol("aiAgent")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        public AgentType[] getAgentTypes() {
            return AgentType.values();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Run AI Agent";
        }

        @POST
        public ListBoxModel doFillApiCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String apiCredentialsId) {
            checkConfigurationPermission(item);
            StandardListBoxModel result = new StandardListBoxModel();
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            item instanceof hudson.model.Queue.Task
                                    ? ((hudson.model.Queue.Task) item).getDefaultAuthentication2()
                                    : ACL.SYSTEM2,
                            item,
                            StringCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(apiCredentialsId);
        }

        @POST
        public FormValidation doCheckApprovalTimeoutSeconds(
                @AncestorInPath Item item,
                @QueryParameter String value,
                @QueryParameter String requireApprovals) {
            checkConfigurationPermission(item);
            if (!isTruthy(requireApprovals)) {
                return FormValidation.ok();
            }
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Timeout is required.");
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed < 1) {
                    return FormValidation.error("Timeout must be at least 1 second.");
                }
                if (parsed > 86400) {
                    return FormValidation.warning(
                            "Large timeout values will block executors for a long time.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Timeout must be a number.");
            }
        }

        private static boolean isTruthy(String raw) {
            if (raw == null) {
                return false;
            }
            String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
            return "true".equals(normalized)
                    || "on".equals(normalized)
                    || "yes".equals(normalized)
                    || "1".equals(normalized);
        }

        private static void checkConfigurationPermission(Item item) {
            if (item != null) {
                item.checkPermission(Item.CONFIGURE);
                return;
            }
            Jenkins.get().checkPermission(Item.CREATE);
        }
    }
}
