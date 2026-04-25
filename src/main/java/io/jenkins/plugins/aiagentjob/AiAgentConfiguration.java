package io.jenkins.plugins.aiagentjob;

/** Shared execution settings used by the AI agent builder step and command execution flow. */
public interface AiAgentConfiguration {
    AiAgentTypeHandler getAgent();

    String getModel();

    String getPrompt();

    String getWorkingDirectory();

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
}
