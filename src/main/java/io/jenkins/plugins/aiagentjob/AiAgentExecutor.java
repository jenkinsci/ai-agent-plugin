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

import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the AI agent subprocess, wires stdout/stderr to the Jenkins build log and the raw JSONL log
 * file, and handles the approval-gate flow when approvals are enabled.
 */
final class AiAgentExecutor {
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
        String model = Util.replaceMacro(Util.fixNull(config.getModel()), env);
        String workDirValue = Util.replaceMacro(Util.fixNull(config.getWorkingDirectory()), env);
        String commandOverride = Util.fixNull(config.getCommandOverride()).trim();

        FilePath runDirectory = resolveRunDirectory(workspace, workDirValue);
        runDirectory.mkdirs();

        EnvVars procEnv = new EnvVars(env);
        procEnv.putAll(
                new LinkedHashMap<>(
                        AiAgentCommandFactory.parseEnvironmentVariables(
                                config.getEnvironmentVariables())));

        // Inject API key from Jenkins Credentials if configured
        String credentialsId = Util.fixEmptyAndTrim(config.getApiCredentialsId());
        if (credentialsId != null) {
            StringCredentials cred =
                    CredentialsProvider.findCredentialById(
                            credentialsId,
                            StringCredentials.class,
                            run,
                            Collections.<DomainRequirement>emptyList());
            if (cred != null) {
                String envVarName = config.getEffectiveApiKeyEnvVar();
                procEnv.put(envVarName, cred.getSecret().getPlainText());
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

        String setupScript = Util.fixNull(config.getSetupScript()).trim();
        if (!setupScript.isEmpty() && !launcher.isUnix()) {
            throw new IOException(
                    "Setup script is currently supported only on Unix agents. "
                            + "Use Command override for Windows nodes.");
        }
        AiAgentExecutionCustomization executionCustomization =
                config.getAgent().prepareExecution(config, workspace, listener);
        procEnv.putAll(executionCustomization.getEnvironment());

        List<String> agentCommand;
        if (!commandOverride.isEmpty()) {
            agentCommand = List.of(commandOverride);
        } else {
            agentCommand = AiAgentCommandFactory.buildDefaultCommand(config, prompt);
        }

        boolean needsShellEnvironmentBootstrap =
                launcher.isUnix() && !executionCustomization.getEnvironment().isEmpty();
        boolean forceUnixShell = config.isDisableInteractive() && launcher.isUnix();
        List<String> command;
        FilePath tempSetupScript = null;
        if ((!setupScript.isEmpty() && launcher.isUnix()) || needsShellEnvironmentBootstrap || forceUnixShell) {
            String combinedScript =
                    buildCombinedScript(
                            setupScript,
                            executionCustomization.getEnvironment(),
                            agentCommand,
                            commandOverride,
                            config.isDisableInteractive());
            tempSetupScript = writeTempScript(workspace, combinedScript);
            command = buildShellCommand(combinedScript, tempSetupScript);
        } else if (!commandOverride.isEmpty()) {
            if (launcher.isUnix()) {
                // Use a non-login shell so injected HOME/USERPROFILE are not overridden.
                command = List.of("/bin/sh", "-c", commandOverride);
            } else {
                String cmd = commandOverride;
                if (config.isDisableInteractive() && !cmd.toUpperCase().contains("< NUL")) {
                    cmd = cmd.endsWith("\n") ? cmd.substring(0, cmd.length() - 1) + " < NUL\n" : cmd + " < NUL";
                }
                command = List.of("cmd", "/c", cmd);
            }
        } else if (config.isDisableInteractive()) {
            command = List.of("cmd", "/c", AiAgentCommandFactory.commandAsString(agentCommand) + " < NUL");
        } else {
            command = agentCommand;
        }

        if (!setupScript.isEmpty()) {
            listener.getLogger().println("[ai-agent] Setup script will run before the agent.");
        }

        String commandLine =
                commandOverride.isEmpty()
                        ? AiAgentCommandFactory.commandAsString(agentCommand)
                        : commandOverride;
        int invocationId =
                action.markStarted(
                        config.getAgent().getDescriptor().getDisplayName(),
                        prompt,
                        model,
                        commandLine,
                        config.isYoloMode(),
                        config.isRequireApprovals());

        File rawLogFile = action.getRawLogFile(invocationId);
        Files.deleteIfExists(rawLogFile.toPath());

        ExecutionRegistry.LiveExecution liveExecution =
                ExecutionRegistry.register(run, invocationId);
        Duration approvalTimeout =
                Duration.ofSeconds(Math.max(1, config.getApprovalTimeoutSeconds()));

        AgentOutputHandler outputHandler =
                new AgentOutputHandler(
                        listener.getLogger(),
                        rawLogFile,
                        liveExecution,
                        config.isRequireApprovals() && !config.isYoloMode(),
                        approvalTimeout,
                        config.getAgent().getLogFormat());
        OutputStream stdoutSink = new NonClosingSynchronizedOutputStream(outputHandler);
        OutputStream stderrSink = new NonClosingSynchronizedOutputStream(outputHandler);

        int exitCode;
        try {
            Proc proc =
                    launcher.launch()
                            .cmds(command)
                            .pwd(runDirectory)
                            .envs(procEnv)
                            .stdout(stdoutSink)
                            .stderr(stderrSink)
                            .quiet(true)
                            .start();
            outputHandler.attach(proc);
            exitCode = proc.join();
        } finally {
            outputHandler.close();
            ExecutionRegistry.unregister(run, invocationId);
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
            executionCustomization.cleanup(listener);
        }

        if (outputHandler.wasDeniedByApproval()) {
            exitCode = 1;
        }
        action.markCompleted(invocationId, exitCode);
        return exitCode;
    }

    private static String buildCombinedScript(
            String setupScript,
            Map<String, String> shellEnvironment,
            List<String> agentCommand,
            String commandOverride,
            boolean disableInteractive) {
        StringBuilder sb = new StringBuilder();
        appendShebangAwarePreamble(sb, setupScript, shellEnvironment);
        if (!commandOverride.isEmpty()) {
            String cmd = commandOverride;
            if (disableInteractive && !cmd.contains("< /dev/null")) {
                cmd = cmd.endsWith("\n") ? cmd.substring(0, cmd.length() - 1) + " < /dev/null\n" : cmd + " < /dev/null";
            }
            sb.append(cmd);
            if (!cmd.endsWith("\n")) {
                sb.append('\n');
            }
        } else {
            sb.append("exec");
            for (String token : agentCommand) {
                sb.append(' ').append(shellQuote(token));
            }
            if (disableInteractive) {
                sb.append(" < /dev/null");
            }
            sb.append('\n');
        }
        return sb.toString();
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
            appendShellExports(sb, shellEnvironment);
            if (end < normalizedSetupScript.length()) {
                sb.append(normalizedSetupScript.substring(end + 1));
                if (!normalizedSetupScript.endsWith("\n")) {
                    sb.append('\n');
                }
            }
            return;
        }
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
        tempScript.chmod(0755);
        return tempScript;
    }

    /**
     * Builds the shell command to run the combined script, honoring a shebang line the same way the
     * Jenkins Shell build step does: if the script starts with {@code #!}, that interpreter is
     * used; otherwise {@code /bin/sh -xe} is used as the default.
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
        return List.of("/bin/sh", "-xe", tempScript.getRemote());
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

    private static FilePath resolveRunDirectory(FilePath workspace, String workDirValue) {
        String trimmed = Util.fixNull(workDirValue).trim();
        if (trimmed.isEmpty()) {
            return workspace;
        }
        return workspace.child(trimmed);
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

    private static final class AgentOutputHandler extends LineTransformationOutputStream {
        private final OutputStream logger;
        private final BufferedWriter rawWriter;
        private final ExecutionRegistry.LiveExecution liveExecution;
        private final boolean approvalsEnabled;
        private final Duration approvalTimeout;
        private final AiAgentLogFormat logFormat;
        private final AtomicLong lineCounter = new AtomicLong();
        private volatile Proc proc;
        private volatile boolean deniedByApproval;

        AgentOutputHandler(
                OutputStream logger,
                File rawLogFile,
                ExecutionRegistry.LiveExecution liveExecution,
                boolean approvalsEnabled,
                Duration approvalTimeout,
                AiAgentLogFormat logFormat)
                throws IOException {
            this.logger = logger;
            this.rawWriter =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    Files.newOutputStream(rawLogFile.toPath()),
                                    StandardCharsets.UTF_8));
            this.liveExecution = liveExecution;
            this.approvalsEnabled = approvalsEnabled;
            this.approvalTimeout = approvalTimeout;
            this.logFormat = logFormat;
        }

        void attach(Proc proc) {
            this.proc = proc;
        }

        boolean wasDeniedByApproval() {
            return deniedByApproval;
        }

        @Override
        protected synchronized void eol(byte[] b, int len) throws IOException {
            String line = new String(b, 0, len, StandardCharsets.UTF_8);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }

            logger.write(line.getBytes(StandardCharsets.UTF_8));
            logger.write('\n');
            logger.flush();

            rawWriter.write(line);
            rawWriter.newLine();
            rawWriter.flush();

            if (!approvalsEnabled) {
                return;
            }

            long id = lineCounter.incrementAndGet();
            AiAgentLogParser.ParsedLine parsedLine =
                    AiAgentLogParser.parseLine(id, line, logFormat);
            if (!parsedLine.isToolCall()) {
                return;
            }

            ExecutionRegistry.PendingApproval pending =
                    liveExecution.createPendingApproval(
                            parsedLine.getToolCallIdOrGenerated(),
                            parsedLine.getToolName(),
                            parsedLine.getSummary());
            logger.write(
                    ("[ai-agent] Approval required: "
                                    + pending.getToolName()
                                    + " ("
                                    + pending.getToolCallId()
                                    + ")\n")
                            .getBytes(StandardCharsets.UTF_8));
            logger.flush();

            ExecutionRegistry.ApprovalDecision decision =
                    liveExecution.awaitDecision(pending, approvalTimeout);
            if (!decision.isApproved()) {
                deniedByApproval = true;
                logger.write(
                        ("[ai-agent] Approval denied: " + decision.getReason() + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                logger.flush();
                Proc currentProc = this.proc;
                if (currentProc != null) {
                    try {
                        currentProc.kill();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else {
                logger.write(
                        ("[ai-agent] Approval granted: " + pending.getToolName() + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                logger.flush();
            }
        }

        @Override
        public synchronized void close() throws IOException {
            super.close();
            rawWriter.close();
        }
    }
}
