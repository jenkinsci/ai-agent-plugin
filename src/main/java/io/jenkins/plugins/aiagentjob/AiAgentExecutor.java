package io.jenkins.plugins.aiagentjob;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.Util;
import hudson.console.LineTransformationOutputStream;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;
import hudson.util.StreamCopyThread;

import net.sf.json.util.JSONUtils;

import org.jenkinsci.plugins.credentialsbinding.masking.SecretPatterns;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs the AI agent subprocess, wires stdout/stderr to the Jenkins build log and the raw JSONL log
 * file, and handles the approval-gate flow when approvals are enabled.
 */
final class AiAgentExecutor {
    private static final Duration ACP_PROTOCOL_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private AiAgentExecutor() {}

    static int execute(
            Run<?, ?> run,
            FilePath workspace,
            EnvVars stepEnv,
            Launcher launcher,
            TaskListener listener,
            AiAgentConfiguration config,
            AiAgentRunAction action)
            throws IOException, InterruptedException {
        EnvVars env = new EnvVars(stepEnv);

        String prompt = Util.replaceMacro(Util.fixNull(config.getPrompt()), env);
        String expandedModel = Util.replaceMacro(Util.fixNull(config.getModel()), env);
        String expandedReasoningEffort =
                Util.replaceMacro(Util.fixNull(config.getReasoningEffort()), env);
        String workDirValue = Util.replaceMacro(Util.fixNull(config.getWorkingDirectory()), env);
        String executablePath =
                Util.replaceMacro(Util.fixNull(config.getExecutablePath()), env).trim();
        String commandOverride = Util.fixNull(config.getCommandOverride()).trim();
        AiAgentTypeHandler agent = config.getAgent();
        AiAgentTypeHandler.ModelSelection modelSelection =
                agent.resolveModelSelection(expandedModel, expandedReasoningEffort);
        String model = modelSelection.getModel();
        String reasoningEffort = modelSelection.getReasoningEffort();
        AiAgentConfiguration commandConfig =
                new ResolvedAiAgentConfiguration(
                        config, expandedModel, expandedReasoningEffort, executablePath);
        AiAgentConfiguration resolvedConfig =
                new ResolvedAiAgentConfiguration(config, model, reasoningEffort, executablePath);
        agent.validateExecution(resolvedConfig);
        boolean manualApprovals =
                resolvedConfig.isRequireApprovals() && !resolvedConfig.isYoloMode();
        AiAgentTypeHandler.AcpExecutionSpec acpExecution =
                manualApprovals ? agent.buildAcpExecution(commandConfig) : null;
        if (manualApprovals && acpExecution == null) {
            throw new IllegalArgumentException(
                    "Manual approvals require an ACP-capable agent command.");
        }

        FilePath runDirectory = resolveRunDirectory(workspace, workDirValue);
        runDirectory.mkdirs();

        EnvVars procEnv = new EnvVars(env);
        procEnv.putAll(
                new LinkedHashMap<>(
                        AiAgentCommandFactory.parseEnvironmentVariables(
                                resolvedConfig.getEnvironmentVariables())));
        List<String> sensitiveValues = new ArrayList<>();

        // Inject API key from Jenkins Credentials if configured
        String credentialsId = Util.fixEmptyAndTrim(resolvedConfig.getApiCredentialsId());
        if (credentialsId != null) {
            StringCredentials cred =
                    CredentialsProvider.findCredentialById(
                            credentialsId,
                            StringCredentials.class,
                            run,
                            Collections.<DomainRequirement>emptyList());
            if (cred != null) {
                String envVarName = resolvedConfig.getEffectiveApiKeyEnvVar();
                String secretValue = cred.getSecret().getPlainText();
                procEnv.put(envVarName, secretValue);
                if (!secretValue.isEmpty()) {
                    sensitiveValues.add(secretValue);
                }
                listener.getLogger()
                        .println(
                                "[ai-agent] API key injected as "
                                        + envVarName
                                        + " from credential '"
                                        + credentialsId
                                        + "'");
            } else {
                listener.getLogger()
                        .println(
                                "[ai-agent] WARNING: Credential '"
                                        + credentialsId
                                        + "' not found. Agent may fail to authenticate.");
            }
        }

        procEnv.put("AI_AGENT_PROMPT", prompt);
        procEnv.put("AI_AGENT_MODEL", model);
        procEnv.put("AI_AGENT_REASONING_EFFORT", reasoningEffort);

        String setupScript = Util.fixNull(resolvedConfig.getSetupScript()).trim();
        if (!setupScript.isEmpty() && !launcher.isUnix()) {
            throw new IOException(
                    "Setup script is currently supported only on Unix agents. "
                            + "Use Command override for Windows nodes.");
        }
        AiAgentExecutionCustomization executionCustomization =
                agent.prepareExecution(resolvedConfig, workspace, listener);
        FilePath tempSetupScript = null;
        try {
            procEnv.putAll(executionCustomization.getEnvironment());

            List<String> agentCommand;
            if (!commandOverride.isEmpty()) {
                agentCommand = List.of(commandOverride);
            } else if (acpExecution != null) {
                agentCommand =
                        AiAgentCommandFactory.applyExecutablePath(
                                acpExecution.getCommand(), resolvedConfig.getExecutablePath());
            } else {
                agentCommand = AiAgentCommandFactory.buildDefaultCommand(commandConfig, prompt);
            }

            boolean needsShellEnvironmentBootstrap =
                    launcher.isUnix() && !executionCustomization.getEnvironment().isEmpty();
            boolean disableInteractive =
                    resolvedConfig.isDisableInteractive() && acpExecution == null;
            List<String> command;
            boolean waitForAcpProcessReady = false;
            if ((!setupScript.isEmpty() && launcher.isUnix()) || needsShellEnvironmentBootstrap) {
                String combinedScript =
                        buildCombinedScript(
                                setupScript,
                                executionCustomization.getEnvironment(),
                                agentCommand,
                                commandOverride,
                                acpExecution == null
                                        ? List.of()
                                        : acpExecution.getAuthenticationMethods().keySet(),
                                acpExecution != null,
                                disableInteractive);
                tempSetupScript = writeTempScript(workspace, combinedScript);
                command = buildShellCommand(combinedScript, tempSetupScript);
                waitForAcpProcessReady = acpExecution != null;
            } else if (!commandOverride.isEmpty()) {
                if (launcher.isUnix()) {
                    // Use a non-login shell so injected HOME/USERPROFILE are not overridden.
                    command =
                            List.of(
                                    "/bin/sh",
                                    "-c",
                                    disableInteractive
                                            ? closeStdinInShell(commandOverride)
                                            : commandOverride);
                } else {
                    command =
                            List.of(
                                    "cmd",
                                    "/c",
                                    disableInteractive
                                            ? "(" + commandOverride + ") < NUL"
                                            : commandOverride);
                }
            } else if (!launcher.isUnix()) {
                command = buildWindowsCommand(agentCommand, disableInteractive);
            } else if (disableInteractive) {
                String nonInteractiveScript =
                        buildCombinedScript("", Map.of(), agentCommand, "", List.of(), false, true);
                tempSetupScript = writeTempScript(workspace, nonInteractiveScript);
                command = buildShellCommand(nonInteractiveScript, tempSetupScript);
            } else {
                command = agentCommand;
            }

            if (!setupScript.isEmpty()) {
                listener.getLogger().println("[ai-agent] Setup script will run before the agent.");
            }

            int invocationId =
                    action.markStarted(
                            agent.getDescriptor().getDisplayName(),
                            "",
                            model,
                            "",
                            resolvedConfig.isYoloMode(),
                            manualApprovals);

            int exitCode = -1;
            Throwable executionFailure = null;
            try {
                AgentOutputHandler outputHandler = null;
                boolean registered = false;
                try {
                    File rawLogFile = action.getRawLogFile(invocationId);
                    Files.deleteIfExists(rawLogFile.toPath());

                    ExecutionRegistry.LiveExecution liveExecution =
                            ExecutionRegistry.register(run, invocationId);
                    registered = true;
                    Duration approvalTimeout =
                            Duration.ofSeconds(
                                    Math.max(1, resolvedConfig.getApprovalTimeoutSeconds()));

                    outputHandler =
                            new AgentOutputHandler(
                                    listener.getLogger(),
                                    rawLogFile,
                                    liveExecution,
                                    sensitiveValues);
                    OutputStream stdoutSink = new NonClosingSynchronizedOutputStream(outputHandler);
                    OutputStream stderrSink = new NonClosingSynchronizedOutputStream(outputHandler);

                    if (acpExecution != null) {
                        exitCode =
                                executeAcpProcess(
                                        launcher,
                                        command,
                                        runDirectory,
                                        procEnv,
                                        listener,
                                        outputHandler,
                                        liveExecution,
                                        approvalTimeout,
                                        prompt,
                                        acpExecution,
                                        waitForAcpProcessReady);
                    } else {
                        Launcher.ProcStarter procStarter =
                                launcher.launch()
                                        .cmds(command)
                                        .pwd(runDirectory)
                                        .envs(procEnv)
                                        .stdout(stdoutSink)
                                        .stderr(stderrSink)
                                        .quiet(true);
                        if (disableInteractive) {
                            procStarter.stdin(null);
                        }
                        Proc proc = startProcess(procStarter, command.get(0));
                        outputHandler.attach(proc);
                        try {
                            exitCode = proc.join();
                            outputHandler.awaitTermination();
                        } catch (IOException | InterruptedException e) {
                            outputHandler.requestTermination();
                            try {
                                outputHandler.awaitTermination();
                            } catch (IOException | InterruptedException terminationFailure) {
                                if (terminationFailure != e) {
                                    e.addSuppressed(terminationFailure);
                                }
                            }
                            throw e;
                        }
                    }
                } finally {
                    try {
                        if (outputHandler != null) {
                            outputHandler.close();
                        }
                    } finally {
                        if (registered) {
                            ExecutionRegistry.unregister(run, invocationId);
                        }
                    }
                }
                exitCode = agent.resolveExitCode(exitCode, action.getRawLogFile(invocationId));
                if (exitCode == 127) {
                    printCommandNotFoundGuidance(listener);
                }
                return exitCode;
            } catch (IOException | InterruptedException | RuntimeException | Error e) {
                executionFailure = e;
                throw e;
            } finally {
                try {
                    action.markCompleted(invocationId, exitCode);
                } catch (IOException completionFailure) {
                    if (executionFailure == null) {
                        throw completionFailure;
                    }
                    executionFailure.addSuppressed(completionFailure);
                }
            }
        } finally {
            try {
                if (tempSetupScript != null) {
                    try {
                        tempSetupScript.delete();
                    } catch (IOException e) {
                        listener.getLogger()
                                .println(
                                        "[ai-agent] Warning: could not delete temp script: "
                                                + e.getMessage());
                    }
                }
            } finally {
                executionCustomization.cleanup(listener);
            }
        }
    }

    private static int executeAcpProcess(
            Launcher launcher,
            List<String> command,
            FilePath runDirectory,
            EnvVars procEnv,
            TaskListener listener,
            AgentOutputHandler outputHandler,
            ExecutionRegistry.LiveExecution liveExecution,
            Duration approvalTimeout,
            String prompt,
            AiAgentTypeHandler.AcpExecutionSpec acpExecution,
            boolean waitForProcessReady)
            throws IOException, InterruptedException {
        Proc proc =
                startProcess(
                        launcher.launch()
                                .cmds(command)
                                .pwd(runDirectory)
                                .envs(procEnv)
                                .readStdout()
                                .readStderr()
                                .writeStdin()
                                .quiet(true),
                        command.get(0));
        outputHandler.attach(proc);

        InputStream stdout = proc.getStdout();
        InputStream stderr = proc.getStderr();
        OutputStream stdin = proc.getStdin();
        if (stdout == null || stderr == null || stdin == null) {
            proc.kill();
            throw new IOException("Jenkins launcher did not provide ACP process streams.");
        }

        StreamCopyThread stderrThread =
                new StreamCopyThread(
                        "ai-agent-acp-stderr",
                        stderr,
                        new NonClosingSynchronizedOutputStream(outputHandler),
                        false);
        stderrThread.start();
        Thread processMonitor = startAcpProcessMonitor(proc, liveExecution);
        try {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            stdout,
                            stdin,
                            outputHandler,
                            liveExecution,
                            approvalTimeout,
                            ACP_PROTOCOL_REQUEST_TIMEOUT,
                            waitForProcessReady);
            return session.execute(
                            runDirectory.getRemote(),
                            prompt,
                            acpExecution.getModel(),
                            acpExecution.getReasoningEffort(),
                            acpExecution.getAuthenticationMethods(),
                            acpExecution.getFallbackAuthenticationMethods(),
                            procEnv)
                    ? 0
                    : 1;
        } finally {
            try {
                stdin.close();
            } finally {
                if (proc.isAlive()) {
                    proc.kill();
                }
                proc.joinWithTimeout(10, TimeUnit.SECONDS, listener);
                processMonitor.join(TimeUnit.SECONDS.toMillis(10));
                stderrThread.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
    }

    private static Thread startAcpProcessMonitor(
            Proc proc, ExecutionRegistry.LiveExecution liveExecution) {
        Thread thread =
                new Thread(
                        () -> {
                            String reason = "agent process exited while waiting for approval";
                            try {
                                proc.join();
                            } catch (IOException e) {
                                reason = "agent process monitor failed: " + e.getMessage();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                reason = "agent process monitor was interrupted";
                            }
                            liveExecution.cancelPendingApprovals(reason);
                        },
                        "ai-agent-acp-process-monitor");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Proc startProcess(Launcher.ProcStarter procStarter, String executable)
            throws IOException {
        try {
            return procStarter.start();
        } catch (IOException e) {
            throw new IOException(
                    "Failed to start executable '"
                            + executable
                            + "'. Ensure it exists and is executable on the build node. Jenkins "
                            + "does not load interactive shell startup files; configure Executable "
                            + "path or update PATH in Additional environment variables or Setup "
                            + "script.",
                    e);
        }
    }

    private static void printCommandNotFoundGuidance(TaskListener listener) {
        listener.getLogger()
                .println(
                        "[ai-agent] Exit code 127 usually means a command was not found. Configure "
                                + "Executable path or update PATH. Setup scripts without a shebang "
                                + "run with /bin/sh -e; use '. file' there, or add #!/bin/bash or "
                                + "#!/bin/zsh before using 'source'.");
    }

    private static String buildCombinedScript(
            String setupScript,
            Map<String, String> shellEnvironment,
            List<String> agentCommand,
            String commandOverride,
            Iterable<String> acpAuthenticationEnvironmentVariables,
            boolean acpMode,
            boolean disableInteractive) {
        StringBuilder sb = new StringBuilder();
        appendShebangAwarePreamble(sb, setupScript, shellEnvironment);
        sb.append("set +x\n");
        if (acpMode) {
            sb.append("printf '\\n'\n");
        }
        appendAcpAuthenticationMarkers(sb, acpAuthenticationEnvironmentVariables);
        if (disableInteractive) {
            sb.append("exec < /dev/null\n");
        }
        if (!commandOverride.isEmpty()) {
            if (acpMode) {
                appendAcpProcessReadyMarker(sb);
            }
            String cmd = commandOverride;
            sb.append(cmd);
            if (!cmd.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            appendExecutableCheck(sb, agentCommand.get(0));
            if (acpMode) {
                appendAcpProcessReadyMarker(sb);
            }
            sb.append("exec");
            for (String token : agentCommand) {
                sb.append(' ').append(shellQuote(token));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String closeStdinInShell(String command) {
        return "exec < /dev/null\n" + command;
    }

    private static void appendExecutableCheck(StringBuilder sb, String executable) {
        sb.append("if ! command -v ")
                .append(shellQuote(executable))
                .append(" >/dev/null 2>&1; then\n")
                .append("  printf '%s\\n' ")
                .append(
                        shellQuote(
                                "[ai-agent] Agent executable '"
                                        + executable
                                        + "' was not found. Configure Executable path or update "
                                        + "PATH in Setup script."))
                .append("\n  exit 127\nfi\n");
    }

    private static void appendAcpAuthenticationMarkers(
            StringBuilder sb, Iterable<String> environmentVariables) {
        for (String name : environmentVariables) {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                continue;
            }
            String marker =
                    "{\"jsonrpc\":\"2.0\",\"method\":\""
                            + AcpClientSession.AUTH_ENVIRONMENT_METHOD
                            + "\",\"params\":{\"name\":\""
                            + name
                            + "\"}}";
            sb.append("if [ -n \"${").append(name).append(":-}\" ]; then\n");
            sb.append("  printf '%s\\n' ").append(shellQuote(marker)).append('\n');
            sb.append("fi\n");
        }
    }

    private static void appendAcpProcessReadyMarker(StringBuilder sb) {
        String marker =
                "{\"jsonrpc\":\"2.0\",\"method\":\""
                        + AcpClientSession.PROCESS_READY_METHOD
                        + "\"}";
        sb.append("printf '%s\\n' ").append(shellQuote(marker)).append('\n');
    }

    private static void appendShebangAwarePreamble(
            StringBuilder sb, String setupScript, Map<String, String> shellEnvironment) {
        String normalizedSetupScript = Util.fixNull(setupScript);
        if (normalizedSetupScript.startsWith("#!")) {
            int end = normalizedSetupScript.indexOf('\n');
            if (end < 0) {
                end = normalizedSetupScript.length();
            }
            sb.append(normalizedSetupScript, 0, end).append('\n');
            sb.append("set +x\n");
            appendShellExports(sb, shellEnvironment);
            if (end < normalizedSetupScript.length()) {
                sb.append(normalizedSetupScript.substring(end + 1));
                if (!normalizedSetupScript.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            return;
        }
        sb.append("set +x\n");
        appendShellExports(sb, shellEnvironment);
        sb.append(normalizedSetupScript);
        if (!normalizedSetupScript.isEmpty() && !normalizedSetupScript.endsWith("\n")) {
            sb.append('\n');
        }
    }

    private static void appendShellExports(StringBuilder sb, Map<String, String> shellEnvironment) {
        for (Map.Entry<String, String> entry : shellEnvironment.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            sb.append("export ")
                    .append(key)
                    .append('=')
                    .append(shellQuote(entry.getValue() == null ? "" : entry.getValue()))
                    .append('\n');
        }
    }

    /**
     * Writes the combined script to an agent-local temp area so the AI agent never sees it in the
     * project workspace.
     */
    private static FilePath writeTempScript(FilePath workspace, String combinedScript)
            throws IOException, InterruptedException {
        FilePath tempDir = AiAgentTempFiles.tempRoot(workspace);
        FilePath tempScript = tempDir.createTextTempFile("ai-agent-setup", ".sh", combinedScript);
        tempScript.chmod(0700);
        return tempScript;
    }

    /**
     * Builds the shell command to run the combined script, honoring a shebang line the same way the
     * Jenkins Shell build step does: if the script starts with {@code #!}, that interpreter is
     * used; otherwise {@code /bin/sh -e} is used as the default.
     */
    private static List<String> buildShellCommand(String setupScript, FilePath tempScript) {
        if (setupScript.startsWith("#!")) {
            int end = setupScript.indexOf('\n');
            if (end < 0) end = setupScript.length();
            String shebangLine = setupScript.substring(0, end).trim();
            List<String> args = new ArrayList<>(Arrays.asList(Util.tokenize(shebangLine)));
            args.set(0, args.get(0).substring(2));
            args.add(tempScript.getRemote());
            return args;
        }
        return List.of("/bin/sh", "-e", tempScript.getRemote());
    }

    private static String shellQuote(String s) {
        if (s.isEmpty()) {
            return "''";
        }
        if (s.matches("[a-zA-Z0-9_./:=@-]+")) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static List<String> buildWindowsCommand(List<String> command) {
        return buildWindowsCommand(command, false);
    }

    static List<String> buildWindowsCommand(List<String> command, boolean disableInteractive) {
        List<String> windowsCommand =
                new ArrayList<>(
                        new ArgumentListBuilder().add(command).toWindowsCommand(true).toList());
        if (disableInteractive) {
            windowsCommand.add(windowsCommand.size() - 3, "<NUL");
        }
        return windowsCommand;
    }

    private static FilePath resolveRunDirectory(FilePath workspace, String workDirValue) {
        String trimmed = Util.fixNull(workDirValue).trim();
        if (trimmed.isEmpty()) {
            return workspace;
        }
        return workspace.child(trimmed);
    }

    private static final class ResolvedAiAgentConfiguration implements AiAgentConfiguration {
        private final AiAgentConfiguration delegate;
        private final String model;
        private final String reasoningEffort;
        private final String executablePath;

        ResolvedAiAgentConfiguration(
                AiAgentConfiguration delegate,
                String model,
                String reasoningEffort,
                String executablePath) {
            this.delegate = delegate;
            this.model = model;
            this.reasoningEffort = reasoningEffort;
            this.executablePath = executablePath;
        }

        @Override
        public AiAgentTypeHandler getAgent() {
            return delegate.getAgent();
        }

        @Override
        public String getModel() {
            return model;
        }

        @Override
        public String getReasoningEffort() {
            return reasoningEffort;
        }

        @Override
        public String getPrompt() {
            return delegate.getPrompt();
        }

        @Override
        public String getWorkingDirectory() {
            return delegate.getWorkingDirectory();
        }

        @Override
        public String getExecutablePath() {
            return executablePath;
        }

        @Override
        public boolean isYoloMode() {
            return delegate.isYoloMode();
        }

        @Override
        public boolean isRequireApprovals() {
            return delegate.isRequireApprovals();
        }

        @Override
        public int getApprovalTimeoutSeconds() {
            return delegate.getApprovalTimeoutSeconds();
        }

        @Override
        public String getCommandOverride() {
            return delegate.getCommandOverride();
        }

        @Override
        public String getExtraArgs() {
            return delegate.getExtraArgs();
        }

        @Override
        public String getEnvironmentVariables() {
            return delegate.getEnvironmentVariables();
        }

        @Override
        public boolean isFailOnAgentError() {
            return delegate.isFailOnAgentError();
        }

        @Override
        public String getSetupScript() {
            return delegate.getSetupScript();
        }

        @Override
        public String getApiCredentialsId() {
            return delegate.getApiCredentialsId();
        }

        @Override
        public String getEffectiveApiKeyEnvVar() {
            return delegate.getEffectiveApiKeyEnvVar();
        }

        @Override
        public boolean isDisableInteractive() {
            return delegate.isDisableInteractive();
        }
    }

    /**
     * Prevents one stream pump from closing the shared output handler while the other stream is
     * still active. The Jenkins launcher may close stdout and stderr independently.
     */
    private static final class NonClosingSynchronizedOutputStream extends OutputStream {
        private final OutputStream delegate;

        NonClosingSynchronizedOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            synchronized (delegate) {
                delegate.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            synchronized (delegate) {
                delegate.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            synchronized (delegate) {
                delegate.flush();
            }
        }

        @Override
        public void close() {}
    }

    static final class AgentOutputHandler extends LineTransformationOutputStream {
        private final OutputStream logger;
        private final BufferedWriter rawWriter;
        private final ExecutionRegistry.LiveExecution liveExecution;
        private final Pattern sensitivePattern;
        private final Object terminationLock = new Object();
        private volatile Proc proc;
        private volatile boolean terminationRequested;
        private volatile Thread terminationThread;
        private volatile IOException terminationFailure;

        AgentOutputHandler(
                OutputStream logger,
                File rawLogFile,
                ExecutionRegistry.LiveExecution liveExecution,
                List<String> sensitiveValues)
                throws IOException {
            this.logger = logger;
            this.rawWriter =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    Files.newOutputStream(rawLogFile.toPath()),
                                    StandardCharsets.UTF_8));
            this.liveExecution = liveExecution;
            this.sensitivePattern =
                    SecretPatterns.getAggregateSecretPattern(
                            expandSensitiveValues(sensitiveValues));
        }

        void attach(Proc proc) {
            synchronized (terminationLock) {
                this.proc = proc;
                startTerminationLocked();
            }
        }

        void requestTermination() {
            liveExecution.cancelPendingApprovals("agent process terminated");
            synchronized (terminationLock) {
                terminationRequested = true;
                startTerminationLocked();
            }
        }

        void awaitTermination() throws IOException, InterruptedException {
            Thread thread;
            synchronized (terminationLock) {
                thread = terminationThread;
            }
            if (thread == null) {
                return;
            }
            thread.join();
            IOException failure = terminationFailure;
            if (failure != null) {
                throw failure;
            }
        }

        private void startTerminationLocked() {
            if (!terminationRequested || proc == null || terminationThread != null) {
                return;
            }
            Proc processToKill = proc;
            Thread thread =
                    new Thread(
                            () -> {
                                try {
                                    processToKill.kill();
                                } catch (IOException e) {
                                    terminationFailure = e;
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    terminationFailure =
                                            new IOException(
                                                    "Interrupted while terminating agent process.",
                                                    e);
                                }
                            },
                            "ai-agent-process-terminator");
            thread.setDaemon(true);
            terminationThread = thread;
            thread.start();
        }

        @Override
        protected synchronized void eol(byte[] b, int len) throws IOException {
            String line = new String(b, 0, len, StandardCharsets.UTF_8);
            recordLine(line);
        }

        synchronized void recordLine(String rawLine) throws IOException {
            String line = maskSensitiveValues(rawLine);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }

            logger.write(line.getBytes(StandardCharsets.UTF_8));
            logger.write('\n');
            logger.flush();

            rawWriter.write(line);
            rawWriter.newLine();
            rawWriter.flush();
        }

        String maskSensitiveValues(String value) {
            if (sensitivePattern.pattern().isEmpty()) {
                return value;
            }
            return sensitivePattern.matcher(value).replaceAll("****");
        }

        private static List<String> expandSensitiveValues(List<String> sensitiveValues) {
            List<String> expanded = new ArrayList<>();
            for (String sensitiveValue : sensitiveValues) {
                if (sensitiveValue == null || sensitiveValue.isEmpty()) {
                    continue;
                }
                addSensitiveValueVariants(expanded, sensitiveValue);
                for (String line : sensitiveValue.split("\\R")) {
                    if (!line.isEmpty()) {
                        addSensitiveValueVariants(expanded, line);
                    }
                }
            }
            return expanded;
        }

        private static void addSensitiveValueVariants(List<String> expanded, String value) {
            expanded.add(value);
            String jsonEscaped = JSONUtils.stripQuotes(JSONUtils.quote(value));
            if (!jsonEscaped.equals(value)) {
                expanded.add(jsonEscaped);
            }
        }

        synchronized void writeStatus(String message) throws IOException {
            String safeMessage = maskSensitiveValues(message);
            logger.write(("[ai-agent] " + safeMessage + "\n").getBytes(StandardCharsets.UTF_8));
            logger.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            super.close();
            rawWriter.close();
        }
    }
}
