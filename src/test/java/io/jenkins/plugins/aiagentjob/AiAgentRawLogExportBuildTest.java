package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;

import hudson.FilePath;
import hudson.model.Executor;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.DumbSlave;
import hudson.util.Secret;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;

import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@WithJenkins
class AiAgentRawLogExportBuildTest {
    private static final String ASSISTANT_LINE =
            "echo '{\"type\":\"assistant\",\"message\":\"exported\"}'";

    private FreeStyleProject newProject(
            JenkinsRule jenkins, String name, Consumer<AiAgentBuilder> cfg) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(name);
        AiAgentBuilder builder = new AiAgentBuilder();
        builder.setAgent(new ClaudeCodeAgentHandler());
        builder.setPrompt("hello");
        builder.setFailOnAgentError(true);
        cfg.accept(builder);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    private static String controllerRawLog(FreeStyleBuild build) throws Exception {
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        return Files.readString(action.getRawLogFile().toPath(), StandardCharsets.UTF_8);
    }

    private static FilePath workspaceFile(FreeStyleBuild build, String path) {
        FilePath workspace = build.getWorkspace();
        assertNotNull(workspace);
        return workspace.child(path);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void copiesMaskedRawLogToWorkspacePath(JenkinsRule jenkins) throws Exception {
        CredentialsProvider.lookupStores(jenkins.jenkins)
                .iterator()
                .next()
                .addCredentials(
                        Domain.global(),
                        new StringCredentialsImpl(
                                CredentialsScope.GLOBAL,
                                "export-api-key",
                                "API key",
                                Secret.fromString("sk-export-secret-98765")));
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export",
                        b -> {
                            b.setApiCredentialsId("export-api-key");
                            b.setWorkingDirectory("src");
                            b.setRawLogPath("ai-logs/run.jsonl");
                            b.setCommandOverride(
                                    ASSISTANT_LINE
                                            + "; echo '{\"type\":\"system\",\"key\":\"'"
                                            + "\"$ANTHROPIC_API_KEY\"'\"}'");
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        FilePath exported = workspaceFile(build, "ai-logs/run.jsonl");
        assertTrue(exported.exists(), "Raw log should be exported relative to workspace root");
        String content = exported.readToString();
        assertEquals(controllerRawLog(build), content);
        assertTrue(content.contains("\"message\":\"exported\""), content);
        assertTrue(content.contains("\"key\":\"****\""), "Exported log must stay masked");
        assertFalse(content.contains("sk-export-secret-98765"), "Secret must not be exported");
        jenkins.assertLogContains(
                "Raw agent log saved to workspace path: ai-logs/run.jsonl", build);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void expandsEnvironmentVariablesInPath(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-expand",
                        b -> {
                            b.setRawLogPath("ai-logs/${BUILD_NUMBER}-run.jsonl");
                            b.setCommandOverride(ASSISTANT_LINE);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        assertTrue(workspaceFile(build, "ai-logs/1-run.jsonl").exists());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void leavesWorkspaceUntouchedWhenPathIsEmpty(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-disabled",
                        b -> b.setCommandOverride(ASSISTANT_LINE));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        FilePath workspace = build.getWorkspace();
        assertNotNull(workspace);
        assertTrue(workspace.list().isEmpty(), "No file should be written to the workspace");
        jenkins.assertLogNotContains("Raw agent log saved", build);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void copiesRawLogWhenAgentFails(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-agent-failure",
                        b -> {
                            b.setRawLogPath("ai-logs/failed.jsonl");
                            b.setCommandOverride(ASSISTANT_LINE + "; exit 3");
                        });

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);

        FilePath exported = workspaceFile(build, "ai-logs/failed.jsonl");
        assertTrue(exported.exists(), "Raw log should be exported after a failed agent run");
        assertEquals(controllerRawLog(build), exported.readToString());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void overwritesExistingFile(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-overwrite",
                        b -> {
                            b.setRawLogPath("run.jsonl");
                            b.setCommandOverride(
                                    "echo stale-content-written-by-agent > run.jsonl; "
                                            + ASSISTANT_LINE);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        String content = workspaceFile(build, "run.jsonl").readToString();
        assertFalse(content.contains("stale-content-written-by-agent"), content);
        assertEquals(controllerRawLog(build), content);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsPathOutsideWorkspaceBeforeLaunchingAgent(JenkinsRule jenkins) throws Exception {
        File marker = new File(jenkins.jenkins.getRootDir(), "raw-log-export-agent-ran");
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-traversal",
                        b -> {
                            b.setRawLogPath("../escape.jsonl");
                            b.setCommandOverride("touch '" + marker.getAbsolutePath() + "'");
                        });

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);

        assertFalse(marker.exists(), "Agent must not start with an invalid raw log path");
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertTrue(action == null || !action.hasInvocations(), "No invocation should be recorded");
        jenkins.assertLogContains("cannot contain '..'", build);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void replacesSymlinkPlantedAtTargetPath(JenkinsRule jenkins) throws Exception {
        File outside = new File(jenkins.jenkins.getRootDir(), "outside-target.txt");
        Files.writeString(outside.toPath(), "original", StandardCharsets.UTF_8);
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-symlink-target",
                        b -> {
                            b.setRawLogPath("ai-logs/run.jsonl");
                            b.setCommandOverride(
                                    "mkdir -p ai-logs; ln -s '"
                                            + outside.getAbsolutePath()
                                            + "' ai-logs/run.jsonl; "
                                            + ASSISTANT_LINE);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        assertEquals("original", Files.readString(outside.toPath(), StandardCharsets.UTF_8));
        FilePath exported = workspaceFile(build, "ai-logs/run.jsonl");
        assertNull(exported.readLink(), "Symlink should be replaced with a regular file");
        assertEquals(controllerRawLog(build), exported.readToString());
        jenkins.assertLogContains("Replacing symlink at raw log path", build);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsParentDirectorySymlinkOutsideWorkspace(JenkinsRule jenkins) throws Exception {
        File outsideDir = new File(jenkins.jenkins.getRootDir(), "outside-dir");
        assertTrue(outsideDir.mkdirs());
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-symlink-parent",
                        b -> {
                            b.setRawLogPath("ai-logs/run.jsonl");
                            b.setCommandOverride(
                                    "ln -s '"
                                            + outsideDir.getAbsolutePath()
                                            + "' ai-logs; "
                                            + ASSISTANT_LINE);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertStatus(Result.FAILURE, project);

        assertFalse(new File(outsideDir, "run.jsonl").exists(), "Must not write outside workspace");
        jenkins.assertLogContains("resolves outside the workspace", build);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void copiesRawLogToRemoteNodeWorkspace(JenkinsRule jenkins) throws Exception {
        DumbSlave agent = jenkins.createOnlineSlave();
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-remote",
                        b -> {
                            b.setRawLogPath("ai-logs/remote.jsonl");
                            b.setCommandOverride(ASSISTANT_LINE);
                        });
        project.setAssignedNode(agent);

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        FilePath exported = workspaceFile(build, "ai-logs/remote.jsonl");
        assertNotNull(exported.getChannel());
        assertTrue(exported.exists(), "Raw log should be exported on the remote node");
        assertEquals(controllerRawLog(build), exported.readToString());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void copiesRawLogWhenBuildIsAborted(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-abort",
                        b -> {
                            b.setRawLogPath("ai-logs/aborted.jsonl");
                            b.setCommandOverride(ASSISTANT_LINE + "; sleep 60");
                        });

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild running = future.waitForStart();
        waitForRawLogLine(running, "\"message\":\"exported\"");

        Executor executor = running.getExecutor();
        assertNotNull(executor);
        executor.interrupt(Result.ABORTED);
        FreeStyleBuild build = future.get(30, TimeUnit.SECONDS);

        assertEquals(Result.ABORTED, build.getResult());
        FilePath exported = workspaceFile(build, "ai-logs/aborted.jsonl");
        assertTrue(exported.exists(), "Raw log should be exported after an abort");
        assertTrue(exported.readToString().contains("\"message\":\"exported\""));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void removesPreviousExportBeforeLaunchingAgent(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-previous",
                        b -> {
                            b.setRawLogPath("ai-logs/run.jsonl");
                            b.setCommandOverride(
                                    "if [ -e ai-logs/run.jsonl ]; then"
                                            + " echo '{\"type\":\"system\",\"previous\":\"visible\"}';"
                                            + " else"
                                            + " echo '{\"type\":\"system\",\"previous\":\"absent\"}';"
                                            + " fi; "
                                            + ASSISTANT_LINE);
                        });

        jenkins.buildAndAssertSuccess(project);
        FreeStyleBuild second = jenkins.buildAndAssertSuccess(project);

        String rawLog = controllerRawLog(second);
        assertTrue(rawLog.contains("\"previous\":\"absent\""), rawLog);
        assertFalse(rawLog.contains("\"previous\":\"visible\""), rawLog);
        jenkins.assertLogContains("Removed existing file at raw log path", second);
        assertEquals(rawLog, workspaceFile(second, "ai-logs/run.jsonl").readToString());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void replacesFifoPlantedAtTargetPath(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-fifo",
                        b -> {
                            b.setRawLogPath("ai-logs/run.jsonl");
                            b.setCommandOverride(
                                    "mkdir -p ai-logs; mkfifo ai-logs/run.jsonl; "
                                            + ASSISTANT_LINE);
                        });

        QueueTaskFuture<FreeStyleBuild> future = project.scheduleBuild2(0);
        assertNotNull(future);
        FreeStyleBuild build = future.get(60, TimeUnit.SECONDS);

        jenkins.assertBuildStatusSuccess(build);
        FilePath exported = workspaceFile(build, "ai-logs/run.jsonl");
        assertEquals(controllerRawLog(build), exported.readToString());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void normalizesBackslashAndDotSegmentsInPath(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-normalize",
                        b -> {
                            b.setRawLogPath(".\\ai-logs\\win.jsonl");
                            b.setCommandOverride(ASSISTANT_LINE);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        FilePath exported = workspaceFile(build, "ai-logs/win.jsonl");
        assertTrue(exported.exists(), "Backslashes should act as directory separators");
        assertEquals(controllerRawLog(build), exported.readToString());
        jenkins.assertLogContains(
                "Raw agent log saved to workspace path: ai-logs/win.jsonl", build);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void acceptsDotPrefixedRootLevelPath(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "raw-log-export-dot-root",
                        b -> {
                            b.setRawLogPath("./run.jsonl");
                            b.setCommandOverride(ASSISTANT_LINE);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        assertEquals(controllerRawLog(build), workspaceFile(build, "run.jsonl").readToString());
    }

    private static void waitForRawLogLine(FreeStyleBuild build, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
            if (action != null && action.getLatestInvocationId() > 0) {
                File raw = action.getRawLogFile(action.getLatestInvocationId());
                if (raw.isFile()
                        && Files.readString(raw.toPath(), StandardCharsets.UTF_8)
                                .contains(expected)) {
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for raw log line: " + expected);
    }
}
