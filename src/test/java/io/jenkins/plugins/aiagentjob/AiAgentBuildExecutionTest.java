package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;

public class AiAgentBuildExecutionTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    private FreeStyleProject newProject(
            String name, java.util.function.Consumer<AiAgentBuilder> cfg) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(name);
        AiAgentBuilder builder = new AiAgentBuilder();
        cfg.accept(builder);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    @Test
    public void runsAgentCommandAndCapturesConversation() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "ai-build-success",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"hello from test\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertFalse(action.getEvents().isEmpty());
        assertTrue(action.getRawLogFile().exists());
    }

    @Test
    public void setupScript_runsBeforeAgent() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "ai-build-setup-script",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("export SETUP_DONE=yes");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"setup=$SETUP_DONE\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

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

        FreeStyleProject project =
                newProject(
                        "ai-build-setup-fail",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("exit 42");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"should not run\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        String log = jenkins.getLog(build);
        assertFalse("Agent should NOT have run", log.contains("should not run"));
    }

    @Test
    public void setupScript_emptyIsSkipped() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "ai-build-no-setup",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"direct run\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertFalse(
                "Should not mention setup script",
                log.contains("Setup script will run before the agent"));
    }

    @Test
    public void setupScript_receivesEnvironmentVariables() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "ai-build-setup-env",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setEnvironmentVariables("CUSTOM_VAR=secret_value_123");
                            b.setSetupScript("echo GOT_$CUSTOM_VAR");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertTrue("Setup script should see custom env vars", log.contains("GOT_secret_value_123"));
    }

    @Test
    public void setupScript_exportsFlowToAgentCommand() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "ai-build-setup-export",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript("export MY_SETUP_VAR=from_setup_script");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"val=$MY_SETUP_VAR\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String log = jenkins.getLog(build);
        assertTrue(
                "Exported var from setup should be visible in agent command",
                log.contains("val=from_setup_script"));
    }

    @Test
    public void codexCustomConfig_createsRunScopedCodexHome() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "ai-build-codex-cfg",
                        b -> {
                            CodexAgentHandler codex = new CodexAgentHandler();
                            codex.setCustomConfigEnabled(true);
                            codex.setCustomConfigToml("[mcp_servers.demo]\ncommand = \"npx\"");
                            b.setAgent(codex);
                            b.setPrompt("hello");
                            b.setCommandOverride(
                                    "cfg=\"$USERPROFILE/.codex/config.toml\"; "
                                            + "if test -f \"$cfg\"; then echo CODEX_CONFIG_FOUND; sed -n '1,200p' \"$cfg\"; "
                                            + "else echo CODEX_CONFIG_MISSING home=$HOME userprofile=$USERPROFILE; fi; "
                                            + "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String log = new String(java.nio.file.Files.readAllBytes(action.getRawLogFile().toPath()));
        assertTrue(
                "Codex config should be found in the run-scoped home",
                log.contains("CODEX_CONFIG_FOUND"));
        assertTrue(
                "Codex config should be written to run-scoped home",
                log.contains("[mcp_servers.demo]"));
        assertTrue("Codex config should preserve TOML content", log.contains("command = \"npx\""));
        assertFalse("Codex config should not be missing", log.contains("CODEX_CONFIG_MISSING"));
    }

    @Test
    public void failsWhenApprovalTimesOut() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "ai-build-approval-timeout",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("needs approval");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(1);
                            b.setFailOnAgentError(true);
                            b.setCommandOverride(
                                    "echo '{\"type\":\"tool_call\",\"tool_name\":\"bash\",\"tool_call_id\":\"call-1\",\"text\":\"ls\"}'; "
                                            + "sleep 2; "
                                            + "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getEvents().stream().anyMatch(e -> "tool_call".equals(e.getCategory())));
    }
}
