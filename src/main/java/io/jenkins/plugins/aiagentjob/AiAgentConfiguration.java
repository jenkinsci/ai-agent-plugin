package io.jenkins.plugins.aiagentjob;

/** Shared execution settings used by the AI agent builder step and command execution flow. */
public interface AiAgentConfiguration {
    AiAgentTypeHandler getAgent();

    String getModel();

    String getReasoningEffort();

    String getPrompt();

    String getWorkingDirectory();

    default String getExecutablePath() {
        return "";
    }

    boolean isYoloMode();

    boolean isRequireApprovals();

    int getApprovalTimeoutSeconds();

    String getCommandOverride();

    String getExtraArgs();

    String getEnvironmentVariables();

    boolean isFailOnAgentError();

    String getSetupScript();

    String getApiCredentialsId();

    String getEffectiveApiKeyEnvVar();

    boolean isDisableInteractive();

    /**
     * Workspace-relative file that receives a copy of the raw agent log after the agent exits.
     * Empty disables the export.
     */
    default String getRawLogPath() {
        return "";
    }
}
