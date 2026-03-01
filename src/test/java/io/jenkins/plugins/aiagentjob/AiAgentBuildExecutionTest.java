package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.Result;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;

public class AiAgentBuildExecutionTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void runsAgentCommandAndCapturesConversation() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "ai-build-success");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("hello");
        project.setCommandOverride(
                "echo '{\"type\":\"assistant\",\"message\":\"hello from test\"}'");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertFalse(action.getEvents().isEmpty());
        assertTrue(action.getRawLogFile().exists());
    }

    @Test
    public void setupScript_runsBeforeAgent() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project =
                jenkins.createProject(AiAgentProject.class, "ai-build-setup-script");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("hello");
        project.setSetupScript("export SETUP_DONE=yes");
        project.setCommandOverride(
                "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"setup=$SETUP_DONE\\\"}\"");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getRawLogFile().exists());
        String rawLog =
                new String(java.nio.file.Files.readAllBytes(action.getRawLogFile().toPath()));
        assertTrue(
                "Agent command should see variable exported by setup script",
                rawLog.contains("setup=yes"));
    }

    @Test
    public void setupScript_failureAbortsBuild() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "ai-build-setup-fail");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("hello");
        project.setSetupScript("exit 42");
        project.setCommandOverride(
                "echo '{\"type\":\"assistant\",\"message\":\"should not run\"}'");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        String log = jenkins.getLog(build);
        assertFalse("Agent should NOT have run", log.contains("should not run"));
    }

    @Test
    public void setupScript_emptyIsSkipped() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "ai-build-no-setup");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("hello");
        project.setSetupScript("");
        project.setCommandOverride("echo '{\"type\":\"assistant\",\"message\":\"direct run\"}'");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertFalse(
                "Should not mention setup script",
                log.contains("Setup script will run before the agent"));
    }

    @Test
    public void setupScript_receivesEnvironmentVariables() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "ai-build-setup-env");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("hello");
        project.setEnvironmentVariables("CUSTOM_VAR=secret_value_123");
        project.setSetupScript("echo GOT_$CUSTOM_VAR");
        project.setCommandOverride("echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertTrue("Setup script should see custom env vars", log.contains("GOT_secret_value_123"));
    }

    @Test
    public void setupScript_exportsFlowToAgentCommand() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project =
                jenkins.createProject(AiAgentProject.class, "ai-build-setup-export");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("hello");
        project.setSetupScript("export MY_SETUP_VAR=from_setup_script");
        project.setCommandOverride(
                "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"val=$MY_SETUP_VAR\\\"}\"");
        project.setFailOnAgentError(true);
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertTrue(
                "Exported var from setup should be visible in agent command",
                log.contains("val=from_setup_script"));
    }

    @Test
    public void failsWhenApprovalTimesOut() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        AiAgentProject project =
                jenkins.createProject(AiAgentProject.class, "ai-build-approval-timeout");
        project.setAgentType(AgentType.CLAUDE_CODE);
        project.setPrompt("needs approval");
        project.setRequireApprovals(true);
        project.setApprovalTimeoutSeconds(1);
        project.setFailOnAgentError(true);
        project.setCommandOverride(
                "echo '{\"type\":\"tool_call\",\"tool_name\":\"bash\",\"tool_call_id\":\"call-1\",\"text\":\"ls\"}'; "
                        + "sleep 2; "
                        + "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
        project.save();

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getEvents().stream().anyMatch(e -> "tool_call".equals(e.getCategory())));
    }
}
