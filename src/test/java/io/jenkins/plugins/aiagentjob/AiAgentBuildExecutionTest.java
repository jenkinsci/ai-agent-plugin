package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;

import edu.umd.cs.findbugs.annotations.NonNull;

import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import hudson.slaves.WorkspaceList;
import hudson.util.Secret;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeLogFormat;
import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeStatsExtractor;
import io.jenkins.plugins.aiagentjob.codex.CodexAgentHandler;
import io.jenkins.plugins.aiagentjob.cursor.CursorAgentHandler;
import io.jenkins.plugins.aiagentjob.grokbuild.GrokBuildAgentHandler;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeAgentHandler;

import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@WithJenkins
class AiAgentBuildExecutionTest {

    public static final class FailingAfterPrepareAgent extends AiAgentTypeHandler {
        private static final String TEMP_DIR_NAME = "ai-agent-cleanup-test";

        @Override
        public String getId() {
            return "FAILING_AFTER_PREPARE";
        }

        @Override
        public String getDefaultApiKeyEnvVar() {
            return "TEST_API_KEY";
        }

        @Override
        public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
            throw new IllegalStateException("failure after prepare");
        }

        @Override
        public AiAgentExecutionCustomization prepareExecution(
                AiAgentConfiguration config, FilePath workspace, TaskListener listener)
                throws IOException, InterruptedException {
            FilePath tempDir = AiAgentTempFiles.tempRoot(workspace).child(TEMP_DIR_NAME);
            tempDir.mkdirs();
            AiAgentExecutionCustomization customization = AiAgentExecutionCustomization.empty();
            customization.addCleanupAction(tempDir::deleteRecursive);
            return customization;
        }

        @Override
        public AiAgentLogFormat getLogFormat() {
            return ClaudeCodeLogFormat.INSTANCE;
        }

        @Override
        public AiAgentStatsExtractor getStatsExtractor() {
            return ClaudeCodeStatsExtractor.INSTANCE;
        }
    }

    private FreeStyleProject newProject(
            JenkinsRule jenkins, String name, java.util.function.Consumer<AiAgentBuilder> cfg)
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(name);
        AiAgentBuilder builder = new AiAgentBuilder();
        cfg.accept(builder);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void runsAgentCommandAndCapturesConversation(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
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
    @EnabledOnOs(OS.LINUX)
    void disableInteractive_closesCommandStdin(JenkinsRule jenkins) throws Exception {
        File fakeBin =
                installExecutable(
                        jenkins,
                        "stdin-codex-bin",
                        "stdin-codex",
                        "#!/bin/sh\n"
                                + "[ \"$1\" = \"--sandbox\" ] || exit 92\n"
                                + "if IFS= read -r unexpected; then exit 91; fi\n"
                                + "echo '{\"type\":\"item.completed\",\"item\":{\"type\":"
                                + "\"agent_message\",\"text\":\"stdin closed\"}}'\n");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-stdin-closed",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("hello");
                            b.setExecutablePath(new File(fakeBin, "stdin-codex").getAbsolutePath());
                            b.setDisableInteractive(true);
                            b.setFailOnAgentError(true);
                        });

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        try {
            FreeStyleBuild build = future.get(10, TimeUnit.SECONDS);
            jenkins.assertBuildStatusSuccess(build);
            AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
            assertNotNull(action);
            assertTrue(Files.readString(action.getRawLogFile().toPath()).contains("stdin closed"));
        } finally {
            OpenStdinLauncherDecorator.closeInput();
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    @TestExtension("disableInteractive_closesCommandStdin")
    public static final class OpenStdinLauncherDecorator extends LauncherDecorator {
        private static volatile PipedOutputStream openInput;

        @NonNull
        @Override
        public Launcher decorate(@NonNull Launcher launcher, @NonNull Node node) {
            return new Launcher.DecoratedLauncher(launcher) {
                @Override
                public Proc launch(ProcStarter starter) throws IOException {
                    PipedOutputStream input = new PipedOutputStream();
                    openInput = input;
                    StringBuilder command = new StringBuilder();
                    for (String argument : starter.cmds()) {
                        if (!command.isEmpty()) {
                            command.append(' ');
                        }
                        command.append('"').append(argument.replace("\"", "\\\"")).append('"');
                    }
                    return getInner()
                            .launch(
                                    starter.cmds(List.of("/bin/sh", "-c", command.toString()))
                                            .stdin(new PipedInputStream(input)));
                }
            };
        }

        static void closeInput() throws IOException {
            if (openInput != null) {
                openInput.close();
                openInput = null;
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_runsBeforeAgent(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
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
                rawLog.contains("setup=yes"),
                "Agent command should see variable exported by setup script");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_failureAbortsBuild(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
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
        assertFalse(log.contains("should not run"), "Agent should NOT have run");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupFailureAfterPrepare_runsAgentCleanup(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-prepare-cleanup",
                        b -> {
                            b.setAgent(new FailingAfterPrepareAgent());
                            b.setPrompt("hello");
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        FilePath workspace = build.getWorkspace();
        assertNotNull(workspace);
        assertFalse(
                AiAgentTempFiles.tempRoot(workspace)
                        .child(FailingAfterPrepareAgent.TEMP_DIR_NAME)
                        .exists());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_emptyIsSkipped(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
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
                log.contains("Setup script will run before the agent"),
                "Should not mention setup script");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_receivesEnvironmentVariables(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
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
        assertTrue(log.contains("GOT_secret_value_123"), "Setup script should see custom env vars");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_exportsFlowToAgentCommand(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
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
                log.contains("val=from_setup_script"),
                "Exported var from setup should be visible in agent command");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void codexCustomConfig_createsRunScopedCodexHome(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-codex-cfg",
                        b -> {
                            CodexAgentHandler codex = new CodexAgentHandler();
                            codex.setCustomConfigEnabled(true);
                            codex.setCustomConfigToml("[mcp_servers.demo]\ncommand = \"npx\"");
                            b.setAgent(codex);
                            b.setPrompt("hello");
                            b.setEnvironmentVariables("CODEX_HOME=/tmp/shared-codex-home");
                            b.setCommandOverride(
                                    "cfg=\"$CODEX_HOME/config.toml\"; "
                                            + "if test -f \"$cfg\"; then echo CODEX_CONFIG_FOUND; sed -n '1,200p' \"$cfg\"; "
                                            + "else echo CODEX_CONFIG_MISSING home=$HOME codex_home=$CODEX_HOME; fi; "
                                            + "echo CODEX_HOME_MODE=$(stat -c %a \"$HOME\"); "
                                            + "echo CODEX_DIR_MODE=$(stat -c %a \"$CODEX_HOME\"); "
                                            + "echo CODEX_CONFIG_MODE=$(stat -c %a \"$cfg\"); "
                                            + "echo CODEX_HOME=$CODEX_HOME; "
                                            + "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String log = new String(java.nio.file.Files.readAllBytes(action.getRawLogFile().toPath()));
        assertTrue(
                log.contains("CODEX_CONFIG_FOUND"),
                "Codex config should be found in the run-scoped home");
        assertTrue(
                log.contains("[mcp_servers.demo]"),
                "Codex config should be written to run-scoped home");
        assertTrue(log.contains("command = \"npx\""), "Codex config should preserve TOML content");
        assertFalse(log.contains("CODEX_CONFIG_MISSING"), "Codex config should not be missing");
        assertFalse(
                log.contains("CODEX_HOME=/tmp/shared-codex-home"),
                "Job-scoped config should override inherited CODEX_HOME");
        assertTrue(log.contains("CODEX_HOME_MODE=700"), "Codex home should be owner-only");
        assertTrue(
                log.contains("CODEX_DIR_MODE=700"), "Codex config directory should be owner-only");
        assertTrue(log.contains("CODEX_CONFIG_MODE=600"), "Codex config should be owner-only");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void failsWhenApprovalTimesOut(JenkinsRule jenkins) throws Exception {
        File fakeBin = installFakeOpenCode(jenkins, "fake-opencode-timeout-bin");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-approval-timeout",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("needs approval");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(1);
                            b.setFailOnAgentError(true);
                            b.setEnvironmentVariables("PATH=" + path);
                        });

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild build = future.get(5, TimeUnit.SECONDS);
        jenkins.assertBuildStatus(Result.FAILURE, build);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(jenkins.getLog(build).contains("approval timed out after 1s"));
        assertTrue(action.getPendingApprovals().isEmpty());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void abortWhileApprovalIsPendingCompletesPromptly(JenkinsRule jenkins) throws Exception {
        File fakeBin = installFakeOpenCode(jenkins, "fake-opencode-abort-bin");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-abort-approval",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("needs approval");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(60);
                            b.setEnvironmentVariables("PATH=" + path);
                        });

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild runningBuild = future.waitForStart();
        waitForPendingApproval(jenkins, runningBuild, future);

        Executor executor = runningBuild.getExecutor();
        assertNotNull(executor);
        long started = System.nanoTime();
        executor.interrupt(Result.ABORTED);

        FreeStyleBuild build = future.get(10, TimeUnit.SECONDS);
        assertEquals(Result.ABORTED, build.getResult());
        assertTrue(
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10),
                "Aborting should not wait for approval timeout");
        assertTrue(
                build.getAction(AiAgentRunAction.class).getPendingApprovals().isEmpty(),
                "Aborting should remove pending approval cards");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void abortWhileAcpWaitsForAgentOutputCompletesPromptly(JenkinsRule jenkins) throws Exception {
        File fakeBin =
                installExecutable(
                        jenkins,
                        "fake-opencode-blocked-output-bin",
                        "opencode",
                        """
                        #!/bin/sh
                        set -eu
                        test "${1:-}" = "acp"
                        IFS= read -r request
                        printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[]}}'
                        IFS= read -r request
                        printf '%s\n' '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[]}}'
                        IFS= read -r request
                        touch acp-prompt-started
                        sleep 5
                        printf '%s\n' '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}'
                        """);
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-abort-acp-output",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("wait for output");
                            b.setRequireApprovals(true);
                            b.setEnvironmentVariables("PATH=" + path);
                        });
        DumbSlave agent = jenkins.createOnlineSlave();
        project.setAssignedNode(agent);

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild runningBuild = future.waitForStart();
        FilePath workspace = null;
        long workspaceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (workspace == null && System.nanoTime() < workspaceDeadline) {
            workspace = runningBuild.getWorkspace();
            Thread.sleep(50);
        }
        assertNotNull(workspace);
        long markerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!workspace.child("acp-prompt-started").exists()
                && System.nanoTime() < markerDeadline) {
            Thread.sleep(50);
        }
        assertTrue(workspace.child("acp-prompt-started").exists());

        Executor executor = runningBuild.getExecutor();
        assertNotNull(executor);
        long started = System.nanoTime();
        executor.interrupt(Result.ABORTED);

        FreeStyleBuild build = future.get(10, TimeUnit.SECONDS);
        assertEquals(Result.ABORTED, build.getResult());
        assertTrue(
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3),
                "Aborting should terminate an ACP process blocked on output");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void codexCommandOverrideCannotBypassApprovalValidation(JenkinsRule jenkins) throws Exception {
        File marker = new File(jenkins.jenkins.getRootDir(), "codex-override-ran");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-codex-approval-override",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("test");
                            b.setRequireApprovals(true);
                            b.setCommandOverride("touch '" + marker.getAbsolutePath() + "'");
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();

        jenkins.assertBuildStatus(Result.FAILURE, build);
        assertFalse(marker.exists(), "Rejected Codex configuration must not launch override");
        assertTrue(jenkins.getLog(build).contains("does not expose"));
    }

    @Test
    void windowsCommandUsesCmdWithoutFlatteningLaunchMode() {
        List<String> command =
                AiAgentExecutor.buildWindowsCommand(
                        List.of(
                                "codex",
                                "exec",
                                "prompt with spaces",
                                "%OPENAI_API_KEY%",
                                "100% literal"));

        assertEquals("cmd.exe", command.get(0));
        assertEquals("/C", command.get(1));
        String commandLine = String.join(" ", command.subList(2, command.size()));
        assertTrue(commandLine.contains("prompt with spaces"));
        assertFalse(commandLine.contains("%OPENAI_API_KEY%"));
        assertTrue(commandLine.contains("100% literal"));

        List<String> nonInteractiveCommand =
                AiAgentExecutor.buildWindowsCommand(List.of("codex", "exec", "prompt"), true);
        assertEquals("<NUL", nonInteractiveCommand.get(nonInteractiveCommand.size() - 4));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScriptDoesNotTraceGeneratedAgentCommand(JenkinsRule jenkins) throws Exception {
        String sensitivePrompt = "sensitive-prompt-not-for-build-log";
        File fakeBin =
                installExecutable(
                        jenkins,
                        "fake-cursor-trace-bin",
                        "agent",
                        "#!/bin/sh\nprintf '%s\\n' '{\"type\":\"assistant\",\"message\":\"ok\"}'\n");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-no-command-trace",
                        b -> {
                            b.setAgent(new CursorAgentHandler());
                            b.setPrompt(sensitivePrompt);
                            b.setSetupScript("true");
                            b.setEnvironmentVariables("PATH=" + path);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        assertFalse(jenkins.getLog(build).contains(sensitivePrompt));
        assertFalse(Files.readString(action.getRawLogFile().toPath()).contains(sensitivePrompt));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScriptDoesNotTraceSensitiveValues(JenkinsRule jenkins) throws Exception {
        String sensitiveValue = "setup-secret-not-for-build-log";
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-no-setup-trace",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setSetupScript(
                                    "export SETUP_SECRET='"
                                            + sensitiveValue
                                            + "'; test -n \"$SETUP_SECRET\"");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"done\"}'");
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        assertFalse(jenkins.getLog(build).contains(sensitiveValue));
        assertFalse(Files.readString(action.getRawLogFile().toPath()).contains(sensitiveValue));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void expandsParameterizedModelAndReasoningEffortInDefaultCommand(JenkinsRule jenkins)
            throws Exception {
        File fakeBin =
                installExecutable(
                        jenkins,
                        "fake-codex-args-bin",
                        "codex",
                        "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-expanded-command-options",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("test");
                            b.setModel("${MODEL_CHOICE}");
                            b.setReasoningEffort("${EFFORT_CHOICE}");
                            b.setSetupScript("true");
                            b.setEnvironmentVariables("PATH=" + path);
                        });
        project.addProperty(
                new ParametersDefinitionProperty(
                        new StringParameterDefinition("MODEL_CHOICE", "gpt-5.5"),
                        new StringParameterDefinition("EFFORT_CHOICE", "xhigh")));

        FreeStyleBuild build =
                project.scheduleBuild2(
                                0,
                                new ParametersAction(
                                        new StringParameterValue("MODEL_CHOICE", "gpt-5.5"),
                                        new StringParameterValue("EFFORT_CHOICE", "xhigh")))
                        .get();
        jenkins.assertBuildStatusSuccess(build);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath());

        assertTrue(rawLog.contains("gpt-5.5"));
        assertTrue(rawLog.contains("model_reasoning_effort=\"xhigh\""));
        assertFalse(rawLog.contains("${MODEL_CHOICE}"));
        assertFalse(rawLog.contains("${EFFORT_CHOICE}"));
        assertEquals("gpt-5.5", action.getInvocationModel(action.getLatestInvocationId()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void expandsAndResolvesParameterizedModelReasoningSuffix(JenkinsRule jenkins) throws Exception {
        File fakeBin =
                installExecutable(
                        jenkins,
                        "fake-codex-suffix-bin",
                        "codex",
                        "#!/bin/sh\nprintf '%s\\n' \"$@\"\nprintf 'env-model=%s\\nenv-effort=%s\\n' \"$AI_AGENT_MODEL\" \"$AI_AGENT_REASONING_EFFORT\"\n");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-expanded-model-suffix",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("test");
                            b.setModel("gpt-5.6-sol:${EFFORT_CHOICE}");
                            b.setSetupScript("true");
                            b.setEnvironmentVariables("PATH=" + path);
                        });
        project.addProperty(
                new ParametersDefinitionProperty(
                        new StringParameterDefinition("EFFORT_CHOICE", "xhigh")));

        FreeStyleBuild build =
                project.scheduleBuild2(
                                0,
                                new ParametersAction(
                                        new StringParameterValue("EFFORT_CHOICE", "xhigh")))
                        .get();
        jenkins.assertBuildStatusSuccess(build);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath());

        assertTrue(rawLog.contains("gpt-5.6-sol"));
        assertFalse(rawLog.contains("gpt-5.6-sol:xhigh"));
        assertTrue(rawLog.contains("model_reasoning_effort=\"xhigh\""));
        assertTrue(rawLog.contains("env-model=gpt-5.6-sol"));
        assertTrue(rawLog.contains("env-effort=xhigh"));
        assertEquals("gpt-5.6-sol", action.getInvocationModel(action.getLatestInvocationId()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void executablePathRunsAgentOutsidePath(JenkinsRule jenkins) throws Exception {
        File fakeBin =
                installExecutable(
                        jenkins,
                        "custom-codex-bin",
                        "custom-codex",
                        "#!/bin/sh\nprintf '%s\\n' "
                                + "'{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\","
                                + "\"text\":\"custom executable ran\"}}'\n");
        String executable = new File(fakeBin, "custom-codex").getAbsolutePath();
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-custom-executable",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("test");
                            b.setExecutablePath("${CUSTOM_AGENT_EXECUTABLE}");
                        });
        project.addProperty(
                new ParametersDefinitionProperty(
                        new StringParameterDefinition("CUSTOM_AGENT_EXECUTABLE", executable)));

        FreeStyleBuild build =
                project.scheduleBuild2(
                                0,
                                new ParametersAction(
                                        new StringParameterValue(
                                                "CUSTOM_AGENT_EXECUTABLE", executable)))
                        .get();

        jenkins.assertBuildStatusSuccess(build);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(
                Files.readString(action.getRawLogFile().toPath())
                        .contains("custom executable ran"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingExecutableReportsConfigurationGuidance(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-missing-executable",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("test");
                            b.setExecutablePath("/definitely/missing/ai-agent-codex");
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();

        jenkins.assertBuildStatus(Result.FAILURE, build);
        String log = jenkins.getLog(build);
        assertTrue(log.contains("Failed to start executable '/definitely/missing/ai-agent-codex'"));
        assertTrue(log.contains("configure Executable path or update PATH"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScriptMissingExecutableReportsShellGuidance(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-setup-missing-executable",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("test");
                            b.setExecutablePath("definitely-missing-ai-agent-codex");
                            b.setSetupScript("true");
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();

        jenkins.assertBuildStatus(Result.FAILURE, build);
        String log = jenkins.getLog(build);
        assertTrue(
                log.contains("Agent executable 'definitely-missing-ai-agent-codex' was not found"));
        assertTrue(log.contains("Setup scripts without a shebang run with /bin/sh -e"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void preservesEscapedReasoningSuffixDuringExecution(JenkinsRule jenkins) throws Exception {
        File fakeBin =
                installExecutable(
                        jenkins,
                        "fake-codex-escaped-suffix-bin",
                        "codex",
                        "#!/bin/sh\nprintf '%s\\n' \"$@\"\nprintf 'env-model=%s\\nenv-effort=%s\\n' \"$AI_AGENT_MODEL\" \"$AI_AGENT_REASONING_EFFORT\"\n");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-escaped-model-suffix",
                        b -> {
                            b.setAgent(new CodexAgentHandler());
                            b.setPrompt("test");
                            b.setModel("provider/example/model::high");
                            b.setSetupScript("true");
                            b.setEnvironmentVariables("PATH=" + path);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath());

        assertTrue(rawLog.contains("provider/example/model:high"));
        assertFalse(rawLog.contains("model_reasoning_effort"));
        assertTrue(rawLog.contains("env-model=provider/example/model:high"));
        assertTrue(rawLog.contains("env-effort="));
        assertEquals(
                "provider/example/model:high",
                action.getInvocationModel(action.getLatestInvocationId()));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void processLaunchFailureCompletesInvocationMetadata(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-launch-failure-metadata",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("test");
                            b.setSetupScript("#!/definitely/missing/ai-agent-interpreter\ntrue");
                            b.setCommandOverride("true");
                        });

        FreeStyleBuild build = project.scheduleBuild2(0).get();
        jenkins.assertBuildStatus(Result.FAILURE, build);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        int invocationId = action.getLatestInvocationId();

        assertEquals(-1, action.getInvocationExitCode(invocationId));
        assertFalse(action.getInvocationCompletedAt(invocationId).isEmpty());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void openCodeAcp_waitsForJenkinsApprovalBeforeToolExecution(JenkinsRule jenkins)
            throws Exception {
        String approvalSecret = "approval-secret-not-for-progressive-events";
        StringCredentialsImpl credential =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "approval-secret",
                        "Approval secret",
                        Secret.fromString(approvalSecret));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), credential);
        File fakeBin = installFakeOpenCode(jenkins, "fake-opencode-bin");
        String executable = new File(fakeBin, "opencode").getAbsolutePath();

        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-opencode-acp-approval",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("create approved.txt");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(30);
                            b.setApiCredentialsId("approval-secret");
                            b.setApiEnvVarName("FAKE_ACP_SECRET_INPUT");
                            b.setModel("test/provider::high");
                            b.setExtraArgs("--variant high --format json --pure");
                            b.setExecutablePath(executable);
                            b.setSetupScript("cd \"${WORKSPACE}\"");
                            b.setFailOnAgentError(true);
                        });
        DumbSlave agent = jenkins.createOnlineSlave();
        project.setAssignedNode(agent);

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild runningBuild = future.waitForStart();
        ExecutionRegistry.PendingApproval pending =
                waitForPendingApproval(jenkins, runningBuild, future);
        assertEquals("****", pending.getInputSummary());
        assertFalse(pending.getInputSummary().contains(approvalSecret));
        AiAgentRunAction action = runningBuild.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        ExecutionRegistry.LiveExecution liveExecution =
                ExecutionRegistry.get(runningBuild, action.getLatestInvocationId());
        assertNotNull(liveExecution);
        FilePath runningWorkspace = runningBuild.getWorkspace();
        assertNotNull(runningWorkspace);
        assertFalse(runningWorkspace.child("approved.txt").exists());
        assertTrue(liveExecution.approve(pending.getId()));

        FreeStyleBuild build = future.get(20, TimeUnit.SECONDS);
        jenkins.assertBuildStatusSuccess(build);
        FilePath buildWorkspace = build.getWorkspace();
        assertNotNull(buildWorkspace);
        assertTrue(buildWorkspace.child("approved.txt").exists());
        assertTrue(
                buildWorkspace
                        .child("approval-response.json")
                        .readToString()
                        .contains("\"optionId\":\"once\""));
        String configRequests = buildWorkspace.child("config-requests.jsonl").readToString();
        assertTrue(
                configRequests.contains("\"configId\":\"model\",\"value\":\"test/provider:high\""));
        assertTrue(configRequests.contains("\"configId\":\"effort\",\"value\":\"high\""));
        assertTrue(buildWorkspace.child("acp-command.txt").readToString().contains("acp --pure"));

        String rawLog = Files.readString(action.getRawLogFile().toPath());
        assertTrue(rawLog.contains("session/request_permission"));
        assertFalse(rawLog.contains("\"configOptions\""));
        assertFalse(rawLog.contains("private-test-command"));
        assertFalse(rawLog.contains("private-test-content"));
        assertFalse(rawLog.contains(approvalSecret));
        assertFalse(jenkins.getLog(build).contains(approvalSecret));
        assertTrue(action.getEvents().stream().anyMatch(e -> "tool_call".equals(e.getCategory())));
        assertTrue(action.getEvents().stream().anyMatch(e -> "assistant".equals(e.getCategory())));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void grokBuildAcp_authenticatesAndWaitsForJenkinsApproval(JenkinsRule jenkins)
            throws Exception {
        String apiKey = "xai-fixture-secret-for-grok-acp";
        StringCredentialsImpl credential =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "grok-xai-key",
                        "Grok API key",
                        Secret.fromString(apiKey));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), credential);
        File fakeBin = installFakeGrok(jenkins, "fake-grok-bin");

        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-grok-acp-approval",
                        b -> {
                            b.setAgent(new GrokBuildAgentHandler());
                            b.setPrompt("create approved.txt");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(30);
                            b.setApiCredentialsId("grok-xai-key");
                            b.setModel("grok-4.5");
                            b.setReasoningEffort("high");
                            b.setExtraArgs(
                                    "--always-approve --disable-web-search "
                                            + "--plugin-dir /tmp/grok-plugin");
                            b.setEnvironmentVariables("PATH=" + path);
                            b.setSetupScript("true");
                            b.setFailOnAgentError(true);
                        });

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild runningBuild = future.waitForStart();
        ExecutionRegistry.PendingApproval pending =
                waitForPendingApproval(jenkins, runningBuild, future);
        assertEquals("****", pending.getInputSummary());
        AiAgentRunAction action = runningBuild.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        ExecutionRegistry.LiveExecution liveExecution =
                ExecutionRegistry.get(runningBuild, action.getLatestInvocationId());
        assertNotNull(liveExecution);
        assertTrue(liveExecution.approve(pending.getId()));

        FreeStyleBuild build = future.get(20, TimeUnit.SECONDS);
        jenkins.assertBuildStatusSuccess(build);
        FilePath workspace = build.getWorkspace();
        assertNotNull(workspace);
        assertTrue(workspace.child("approved.txt").exists());
        assertTrue(
                workspace
                        .child("approval-response.json")
                        .readToString()
                        .contains("\"optionId\":\"allow-once\""));

        String acpCommand = workspace.child("acp-command.txt").readToString();
        assertTrue(acpCommand.contains("--no-auto-update --permission-mode default"));
        assertTrue(acpCommand.contains("--disable-web-search agent"));
        assertTrue(acpCommand.contains("--model grok-4.5"));
        assertTrue(acpCommand.contains("--reasoning-effort high"));
        assertTrue(acpCommand.endsWith("--plugin-dir /tmp/grok-plugin stdio\n"));
        assertFalse(acpCommand.contains("--always-approve"));

        String rawLog = Files.readString(action.getRawLogFile().toPath());
        assertTrue(rawLog.contains("session/request_permission"));
        assertFalse(rawLog.contains(apiKey));
        assertFalse(jenkins.getLog(build).contains(apiKey));
        assertTrue(action.getEvents().stream().anyMatch(e -> "tool_call".equals(e.getCategory())));
        assertTrue(
                action.getEvents().stream().anyMatch(e -> "tool_result".equals(e.getCategory())));
        assertTrue(action.getEvents().stream().anyMatch(e -> "assistant".equals(e.getCategory())));
        AgentUsageStats stats = action.getUsageStats();
        assertEquals(300, stats.getInputTokens());
        assertEquals(900, stats.getCacheReadTokens());
        assertEquals(1280, stats.getTotalTokens());
        assertEquals("grok-4.5-build", stats.getDetectedModel());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void grokBuild_terminalFailuresPropagateWithFailOnAgentError(JenkinsRule jenkins)
            throws Exception {
        List<Map.Entry<String, String>> failureEvents =
                List.of(
                        Map.entry(
                                "cancelled",
                                "{\"type\":\"end\",\"stopReason\":\"Cancelled\",\"usage\":{\"inputTokens\":10,\"outputTokens\":2}}"),
                        Map.entry("max-turns", "{\"type\":\"max_turns_reached\",\"maxTurns\":4}"),
                        Map.entry(
                                "acp-max-tokens",
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"max_tokens\"}}"),
                        Map.entry("error", "{\"type\":\"error\",\"message\":\"request failed\"}"));

        for (Map.Entry<String, String> failureEvent : failureEvents) {
            String caseName = failureEvent.getKey();
            File fakeBin =
                    installExecutable(
                            jenkins,
                            "fake-grok-" + caseName + "-bin",
                            "grok",
                            "#!/bin/sh\nprintf '%s\\n' '" + failureEvent.getValue() + "'\n");
            String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
            FreeStyleProject project =
                    newProject(
                            jenkins,
                            "ai-build-grok-" + caseName,
                            b -> {
                                b.setAgent(new GrokBuildAgentHandler());
                                b.setPrompt("trigger " + caseName);
                                b.setEnvironmentVariables("PATH=" + path);
                                b.setSetupScript("true");
                                b.setDisableInteractive(true);
                                b.setFailOnAgentError(true);
                            });

            FreeStyleBuild build = project.scheduleBuild2(0).get();
            jenkins.assertBuildStatus(Result.FAILURE, build);
            AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
            assertNotNull(action);
            assertEquals(Integer.valueOf(1), action.getExitCode(), caseName);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void grokBuildAcp_detectsApiKeyExportedBySetupScript(JenkinsRule jenkins) throws Exception {
        String apiKey = "xai-setup-fixture-key";
        File fakeBin = installFakeGrok(jenkins, "fake-grok-setup-bin");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-grok-setup-auth",
                        b -> {
                            b.setAgent(new GrokBuildAgentHandler());
                            b.setPrompt("create approved.txt");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(30);
                            b.setEnvironmentVariables(
                                    "PATH="
                                            + path
                                            + "\nGROK_FIXTURE_PERMISSION_INPUT=fixture-command");
                            b.setSetupScript(
                                    "printf 'setup-progress'\nexport XAI_API_KEY=" + apiKey);
                            b.setFailOnAgentError(true);
                        });

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild runningBuild = future.waitForStart();
        ExecutionRegistry.PendingApproval pending =
                waitForPendingApproval(jenkins, runningBuild, future);
        assertEquals("fixture-command", pending.getInputSummary());
        AiAgentRunAction action = runningBuild.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        ExecutionRegistry.LiveExecution liveExecution =
                ExecutionRegistry.get(runningBuild, action.getLatestInvocationId());
        assertNotNull(liveExecution);
        assertTrue(liveExecution.approve(pending.getId()));

        FreeStyleBuild build = future.get(20, TimeUnit.SECONDS);
        jenkins.assertBuildStatusSuccess(build);
        FilePath workspace = build.getWorkspace();
        assertNotNull(workspace);
        assertTrue(workspace.child("approved.txt").exists());
        String rawLog = Files.readString(action.getRawLogFile().toPath());
        assertTrue(rawLog.contains("setup-progress"));
        assertFalse(rawLog.contains(AcpClientSession.AUTH_ENVIRONMENT_METHOD));
        assertFalse(rawLog.contains(AcpClientSession.PROCESS_READY_METHOD));
        assertFalse(rawLog.contains(apiKey));
        assertFalse(jenkins.getLog(build).contains(apiKey));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void openCodeAcp_processExitCancelsPendingApproval(JenkinsRule jenkins) throws Exception {
        File fakeBin = installFakeOpenCode(jenkins, "fake-opencode-exit-bin");
        String path = fakeBin.getAbsolutePath() + File.pathSeparator + System.getenv("PATH");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-opencode-acp-exit",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("request then exit");
                            b.setRequireApprovals(true);
                            b.setApprovalTimeoutSeconds(30);
                            b.setEnvironmentVariables(
                                    "PATH=" + path + "\nFAKE_ACP_EXIT_AFTER_PERMISSION=1");
                            b.setFailOnAgentError(true);
                        });

        long started = System.nanoTime();
        FreeStyleBuild build = project.scheduleBuild2(0).get(10, TimeUnit.SECONDS);

        jenkins.assertBuildStatus(Result.FAILURE, build);
        assertTrue(
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10),
                "Agent exit should not wait for approval timeout");
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getPendingApprovals().isEmpty());
        assertTrue(jenkins.getLog(build).contains("agent process exited"));
    }

    private static ExecutionRegistry.PendingApproval waitForPendingApproval(
            JenkinsRule jenkins, FreeStyleBuild build, QueueTaskFuture<FreeStyleBuild> future)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
            int invocationId = action == null ? 0 : action.getLatestInvocationId();
            ExecutionRegistry.LiveExecution liveExecution =
                    invocationId == 0 ? null : ExecutionRegistry.get(build, invocationId);
            if (liveExecution != null && !liveExecution.getPendingApprovals().isEmpty()) {
                return liveExecution.getPendingApprovals().get(0);
            }
            if (future.isDone()) {
                FreeStyleBuild completed = future.get();
                throw new AssertionError(
                        "AI agent build completed before requesting approval:\n"
                                + jenkins.getLog(completed));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("AI agent approval request did not reach Jenkins.");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void commandOverride_receivesStepEnvironmentVariables(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-step-env",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("prompt-${SURROUNDING_VAR}");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"step=$SURROUNDING_VAR\\\"}\"");
                            b.setFailOnAgentError(true);
                        });
        project.addProperty(
                new ParametersDefinitionProperty(
                        new StringParameterDefinition("SURROUNDING_VAR", "default")));

        FreeStyleBuild build =
                project.scheduleBuild2(
                                0,
                                new ParametersAction(
                                        new StringParameterValue(
                                                "SURROUNDING_VAR", "from-parameter")))
                        .get();
        jenkins.assertBuildStatusSuccess(build);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath());
        assertTrue(
                rawLog.contains("step=from-parameter"),
                "Command override should inherit step-scoped env vars");
        AiAgentRunAction.InvocationRecord invocation = action.getInvocations().get(0);
        assertEquals("", invocation.getPrompt());
        assertEquals("", invocation.getCommandLine());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void setupScript_usesAgentLocalTempPathOnRemoteNode(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = jenkins.createOnlineSlave();
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-remote-setup-temp",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("hello");
                            b.setSetupScript(
                                    "echo SETUP_SCRIPT_PATH=$0; "
                                            + "echo SETUP_SCRIPT_MODE=$(stat -c %a \"$0\")");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"remote\"}'");
                            b.setFailOnAgentError(true);
                        });
        project.setAssignedNode(agent);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        FilePath workspace = project.getSomeWorkspace();
        assertNotNull(workspace);
        FilePath tempRoot = WorkspaceList.tempDir(workspace);
        assertNotNull(tempRoot);

        String log = jenkins.getLog(build);
        assertTrue(
                log.contains("SETUP_SCRIPT_PATH=" + tempRoot.getRemote()),
                "Setup script should run from the agent temp area");
        assertTrue(log.contains("SETUP_SCRIPT_MODE=700"), "Temp script should be owner-only");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void codexCustomConfig_usesAgentLocalTempPathOnRemoteNode(JenkinsRule jenkins)
            throws Exception {
        DumbSlave agent = jenkins.createOnlineSlave();
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "ai-build-remote-codex-home",
                        b -> {
                            CodexAgentHandler codex = new CodexAgentHandler();
                            codex.setCustomConfigEnabled(true);
                            codex.setCustomConfigToml("model = \"gpt-5.5\"");
                            b.setAgent(codex);
                            b.setPrompt("hello");
                            b.setCommandOverride(
                                    "cfg=\"$CODEX_HOME/config.toml\"; "
                                            + "echo \"{\\\"type\\\":\\\"assistant\\\",\\\"message\\\":\\\"cfg=$cfg home=$HOME codex_home=$CODEX_HOME\\\"}\"");
                            b.setFailOnAgentError(true);
                        });
        project.setAssignedNode(agent);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        FilePath workspace = project.getSomeWorkspace();
        assertNotNull(workspace);
        FilePath tempRoot = WorkspaceList.tempDir(workspace);
        assertNotNull(tempRoot);

        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        String rawLog = Files.readString(action.getRawLogFile().toPath());
        String expectedHomePrefix =
                "home=" + tempRoot.getRemote() + File.separator + "ai-agent-codex-home-";
        assertTrue(
                rawLog.contains(expectedHomePrefix),
                "Codex home should come from the agent temp area");
        assertTrue(
                rawLog.contains("/.codex/config.toml"),
                "Codex config path should resolve inside the run-scoped home");
    }

    private static File installFakeOpenCode(JenkinsRule jenkins, String directoryName)
            throws Exception {
        return installFakeAcp(jenkins, directoryName, "opencode", "fake-opencode-acp.sh");
    }

    private static File installFakeGrok(JenkinsRule jenkins, String directoryName)
            throws Exception {
        return installFakeAcp(jenkins, directoryName, "grok", "fake-grok-acp.sh");
    }

    private static File installFakeAcp(
            JenkinsRule jenkins, String directoryName, String executableName, String fixtureName)
            throws Exception {
        File fakeBin = new File(jenkins.jenkins.getRootDir(), directoryName);
        assertTrue(fakeBin.mkdirs());
        File executable = new File(fakeBin, executableName);
        try (InputStream fixture =
                AiAgentBuildExecutionTest.class.getResourceAsStream(
                        "/io/jenkins/plugins/aiagentjob/fixtures/" + fixtureName)) {
            assertNotNull(fixture);
            Files.copy(fixture, executable.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        assertTrue(executable.setExecutable(true));
        return fakeBin;
    }

    private static File installExecutable(
            JenkinsRule jenkins, String directoryName, String executableName, String content)
            throws Exception {
        File fakeBin = new File(jenkins.jenkins.getRootDir(), directoryName);
        assertTrue(fakeBin.mkdirs());
        File executable = new File(fakeBin, executableName);
        Files.writeString(executable.toPath(), content);
        assertTrue(executable.setExecutable(true));
        return fakeBin;
    }
}
