package io.jenkins.plugins.aiagentjob;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.Util;
import hudson.console.LineTransformationOutputStream;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;

import org.apache.commons.lang.StringUtils;
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
            AbstractBuild<?, ?> build,
            Launcher launcher,
            BuildListener listener,
            AiAgentProject project,
            AiAgentRunAction action)
            throws IOException, InterruptedException {
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            throw new IOException("Workspace is not available for this build.");
        }

        EnvVars env = build.getEnvironment(listener);
        env.putAll(build.getBuildVariables());

        String prompt = Util.replaceMacro(Util.fixNull(project.getPrompt()), env);
        String model = Util.replaceMacro(Util.fixNull(project.getModel()), env);
        String workDirValue = Util.replaceMacro(Util.fixNull(project.getWorkingDirectory()), env);
        String commandOverride = Util.replaceMacro(Util.fixNull(project.getCommandOverride()), env);
        commandOverride = commandOverride.trim();

        FilePath runDirectory = resolveRunDirectory(workspace, workDirValue);
        runDirectory.mkdirs();

        Map<String, String> extraEnv =
                new LinkedHashMap<>(
                        AiAgentCommandFactory.parseEnvironmentVariables(
                                project.getEnvironmentVariables()));

        // Inject API key from Jenkins Credentials if configured
        String credentialsId = Util.fixEmptyAndTrim(project.getApiCredentialsId());
        if (credentialsId != null) {
            StringCredentials cred =
                    CredentialsProvider.findCredentialById(
                            credentialsId,
                            StringCredentials.class,
                            (Run<?, ?>) build,
                            Collections.<DomainRequirement>emptyList());
            if (cred != null) {
                String envVarName = project.getEffectiveApiKeyEnvVar();
                extraEnv.put(envVarName, cred.getSecret().getPlainText());
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

        if (project.getAgentType() == AgentType.OPENCODE) {
            if (project.isYoloMode()) {
                extraEnv.put(
                        "OPENCODE_PERMISSION",
                        "{\"edit\":\"allow\",\"bash\":\"allow\",\"webfetch\":\"allow\",\"external_directory\":\"allow\",\"doom_loop\":\"allow\"}");
            } else if (project.isRequireApprovals()) {
                extraEnv.put(
                        "OPENCODE_PERMISSION",
                        "{\"edit\":\"ask\",\"bash\":\"ask\",\"webfetch\":\"ask\",\"external_directory\":\"ask\",\"doom_loop\":\"ask\"}");
            }
        }
        extraEnv.put("AI_AGENT_PROMPT", prompt);
        extraEnv.put("AI_AGENT_MODEL", model);
        extraEnv.put("AI_AGENT_JOB", build.getProject().getFullName());
        extraEnv.put("AI_AGENT_BUILD_NUMBER", String.valueOf(build.getNumber()));

        String setupScript = Util.replaceMacro(Util.fixNull(project.getSetupScript()), env).trim();

        List<String> agentCommand;
        if (!commandOverride.isEmpty()) {
            agentCommand = List.of(commandOverride);
        } else {
            agentCommand = AiAgentCommandFactory.buildDefaultCommand(project, prompt);
        }

        List<String> command;
        FilePath tempSetupScript = null;
        if (!setupScript.isEmpty() && launcher.isUnix()) {
            String combinedScript = buildCombinedScript(setupScript, agentCommand, commandOverride);
            tempSetupScript = writeTempScript(workspace, combinedScript);
            command = buildShellCommand(setupScript, tempSetupScript);
        } else if (!commandOverride.isEmpty()) {
            if (launcher.isUnix()) {
                command = List.of("/bin/sh", "-lc", commandOverride);
            } else {
                command = List.of("cmd", "/c", commandOverride);
            }
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
        action.markStarted(
                project.getAgentType(),
                model,
                commandLine,
                project.isYoloMode(),
                project.isRequireApprovals());

        File rawLogFile = action.getRawLogFile();
        Files.deleteIfExists(rawLogFile.toPath());

        ExecutionRegistry.LiveExecution liveExecution = ExecutionRegistry.register(build);
        Duration approvalTimeout =
                Duration.ofSeconds(Math.max(1, project.getApprovalTimeoutSeconds()));

        AgentOutputHandler outputHandler =
                new AgentOutputHandler(
                        listener.getLogger(),
                        rawLogFile,
                        liveExecution,
                        project.isRequireApprovals() && !project.isYoloMode(),
                        approvalTimeout);

        int exitCode;
        try {
            Proc proc =
                    launcher.launch()
                            .cmds(command)
                            .pwd(runDirectory)
                            .envs(extraEnv)
                            .stdout(outputHandler)
                            .stderr(outputHandler)
                            .quiet(true)
                            .start();
            outputHandler.attach(proc);
            exitCode = proc.join();
        } finally {
            outputHandler.close();
            ExecutionRegistry.unregister(build);
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
        }

        if (outputHandler.wasDeniedByApproval()) {
            exitCode = 1;
        }
        action.markCompleted(exitCode);
        return exitCode;
    }

    /**
     * Builds the combined script that sources the setup preamble and then execs the agent command
     * in the same shell session, so exported variables flow through.
     */
    private static String buildCombinedScript(
            String setupScript, List<String> agentCommand, String commandOverride) {
        StringBuilder sb = new StringBuilder();
        sb.append(setupScript);
        if (!setupScript.endsWith("\n")) {
            sb.append('\n');
        }
        if (!commandOverride.isEmpty()) {
            sb.append("exec ").append(commandOverride).append('\n');
        } else {
            sb.append("exec");
            for (String token : agentCommand) {
                sb.append(' ').append(shellQuote(token));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Writes the combined script to a temp file in the system temp directory so the AI agent never
     * sees it in the project workspace.
     */
    private static FilePath writeTempScript(FilePath workspace, String combinedScript)
            throws IOException, InterruptedException {
        FilePath tempDir =
                new FilePath(
                        workspace.getChannel(),
                        new File(System.getProperty("java.io.tmpdir")).getAbsolutePath());
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
        String trimmed = StringUtils.trimToEmpty(workDirValue);
        if (trimmed.isEmpty()) {
            return workspace;
        }
        if (trimmed.startsWith("/") || trimmed.matches("^[A-Za-z]:\\\\.*")) {
            return new FilePath(workspace.getChannel(), trimmed);
        }
        return workspace.child(trimmed);
    }

    private static final class AgentOutputHandler extends LineTransformationOutputStream {
        private final OutputStream logger;
        private final BufferedWriter rawWriter;
        private final ExecutionRegistry.LiveExecution liveExecution;
        private final boolean approvalsEnabled;
        private final Duration approvalTimeout;
        private final AtomicLong lineCounter = new AtomicLong();
        private volatile Proc proc;
        private volatile boolean deniedByApproval;

        AgentOutputHandler(
                OutputStream logger,
                File rawLogFile,
                ExecutionRegistry.LiveExecution liveExecution,
                boolean approvalsEnabled,
                Duration approvalTimeout)
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
            AiAgentLogParser.ParsedLine parsedLine = AiAgentLogParser.parseLine(id, line);
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
