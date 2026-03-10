package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Integration tests that feed recorded agent conversations through Jenkins builds using command
 * overrides, then verify the log parser produces correct events.
 */
public class AiAgentRecordedConversationTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    private String buildEchoScript(String fixtureName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("fixtures/" + fixtureName);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().collect(Collectors.toList());
            StringBuilder script = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                String escaped = lines.get(i).replace("\\", "\\\\").replace("'", "'\\''");
                if (i > 0) {
                    script.append(" && ");
                }
                script.append("echo '").append(escaped).append("'");
            }
            return script.toString();
        }
    }

    private FreeStyleProject buildProjectWithFixture(
            String jobName, AiAgentTypeHandler agent, String fixtureName) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(jobName);
        AiAgentBuilder builder = new AiAgentBuilder();
        builder.setAgent(agent);
        builder.setPrompt("test prompt");
        builder.setCommandOverride(buildEchoScript(fixtureName));
        builder.setFailOnAgentError(true);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    @Test
    public void claudeCodeRecording_producesCorrectEvents() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "claude-recording",
                        new ClaudeCodeAgentHandler(),
                        "claude-code-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull("Should have AiAgentRunAction", action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse("Should have events", events.isEmpty());

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue("Should have system events", cats.contains("system"));
        assertTrue("Should have thinking events", cats.contains("thinking"));
        assertTrue("Should have tool_call events", cats.contains("tool_call"));
        assertTrue("Should have assistant events", cats.contains("assistant"));

        assertTrue("Raw log file should exist", action.getRawLogFile().exists());
        assertTrue("Raw log file should not be empty", action.getRawLogFile().length() > 0);
    }

    @Test
    public void codexRecording_producesCorrectEvents() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "codex-recording", new CodexAgentHandler(), "codex-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse("Should have events", events.isEmpty());

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue("Should have thinking", cats.contains("thinking"));
        assertTrue("Should have tool_call", cats.contains("tool_call"));
        assertTrue("Should have tool_result", cats.contains("tool_result"));
        assertTrue("Should have assistant", cats.contains("assistant"));
        assertEquals("Current Codex fixture should render 6 visible events", 6, events.size());
    }

    @Test
    public void cursorAgentRecording_producesCorrectEvents() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "cursor-recording",
                        new CursorAgentHandler(),
                        "cursor-agent-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse("Should have events", events.isEmpty());

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue("Should have system", cats.contains("system"));
        assertTrue("Should have thinking", cats.contains("thinking"));
        assertTrue("Should have tool_call", cats.contains("tool_call"));
    }

    @Test
    public void geminiCliRecording_producesCorrectEvents() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "gemini-recording",
                        new GeminiCliAgentHandler(),
                        "gemini-cli-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse("Should have events", events.isEmpty());

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue("Should have system", cats.contains("system"));
        assertTrue("Should have user", cats.contains("user"));
        assertTrue("Should have tool_call", cats.contains("tool_call"));
        assertTrue("Should have assistant", cats.contains("assistant"));
        assertFalse("Empty Gemini tool results should stay hidden", cats.contains("tool_result"));
        assertFalse("Stats-only Gemini result should stay hidden", cats.contains("result"));
        assertEquals("Current Gemini fixture should render 5 visible events", 5, events.size());
        long assistantCount =
                events.stream().filter(e -> "assistant".equals(e.getCategory())).count();
        assertEquals("Gemini deltas should merge into one assistant event", 1, assistantCount);
    }

    @Test
    public void openCodeRecording_showsCompletedToolOutputs() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "opencode-recording",
                        new OpenCodeAgentHandler(),
                        "opencode-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> toolResults =
                action.getEvents().stream()
                        .filter(e -> "tool_result".equals(e.getCategory()))
                        .collect(Collectors.toList());
        assertEquals("Should have 2 visible completed tool results", 2, toolResults.size());
        assertTrue(
                "OpenCode tool result output should be rendered from nested state.output",
                toolResults.stream().allMatch(e -> !e.getToolOutput().isEmpty()));
        assertEquals(
                "Current OpenCode fixture should render 3 visible events",
                3,
                action.getEvents().size());
    }

    @Test
    public void errorRecording_buildSucceedsWithExitZero() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "error-recording",
                        new ClaudeCodeAgentHandler(),
                        "error-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertEquals(Integer.valueOf(0), action.getExitCode());
    }

    @Test
    public void streamingRecording_handlesStreamEvents() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "streaming-recording",
                        new ClaudeCodeAgentHandler(),
                        "claude-code-streaming.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse("Should have events", events.isEmpty());

        boolean hasThinking = events.stream().anyMatch(e -> "thinking".equals(e.getCategory()));
        boolean hasAssistant = events.stream().anyMatch(e -> "assistant".equals(e.getCategory()));
        assertTrue("Should have thinking from stream events", hasThinking);
        assertTrue("Should have assistant from stream events", hasAssistant);
    }

    @Test
    public void actionMetadata_isPopulatedCorrectly() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                buildProjectWithFixture(
                        "metadata-test",
                        new ClaudeCodeAgentHandler(),
                        "claude-code-conversation.jsonl");
        ((AiAgentBuilder) project.getBuildersList().get(0)).setModel("claude-opus-4");
        project.save();

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        assertEquals("Claude Code", action.getAgentType());
        assertEquals("claude-opus-4", action.getModel());
        assertNotNull("Should have started timestamp", action.getStartedAt());
        assertNotNull("Should have completed timestamp", action.getCompletedAt());
        assertNotNull("Should have exit code", action.getExitCode());
        assertEquals(Integer.valueOf(0), action.getExitCode());
        assertFalse("Build should not be live after completion", action.isLive());
    }
}
