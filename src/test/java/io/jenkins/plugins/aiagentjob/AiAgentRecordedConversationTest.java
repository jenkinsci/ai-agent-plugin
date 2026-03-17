package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.codex.CodexAgentHandler;
import io.jenkins.plugins.aiagentjob.cursor.CursorAgentHandler;
import io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeAgentHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Integration tests that feed recorded agent conversations through Jenkins builds using command
 * overrides, then verify the log parser produces correct events.
 */
@WithJenkins
class AiAgentRecordedConversationTest {

    private File writeFixtureToTempFile(String fixtureName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("fixtures/" + fixtureName)) {
            byte[] data = is.readAllBytes();
            File temp = File.createTempFile("fixture-", ".jsonl");
            temp.deleteOnExit();
            Files.write(temp.toPath(), data);
            return temp;
        }
    }

    private FreeStyleProject buildProjectWithFixture(
            JenkinsRule jenkins, String jobName, AiAgentTypeHandler agent, String fixtureName)
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(jobName);
        AiAgentBuilder builder = new AiAgentBuilder();
        builder.setAgent(agent);
        builder.setPrompt("test prompt");
        File fixtureFile = writeFixtureToTempFile(fixtureName);
        builder.setCommandOverride("cat " + fixtureFile.getAbsolutePath());
        builder.setFailOnAgentError(true);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void claudeCodeRecording_producesCorrectEvents(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
                        "claude-recording",
                        new ClaudeCodeAgentHandler(),
                        "claude-code-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action, "Should have AiAgentRunAction");

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue(cats.contains("system"), "Should have system events");
        assertTrue(cats.contains("thinking"), "Should have thinking events");
        assertTrue(cats.contains("tool_call"), "Should have tool_call events");
        assertTrue(cats.contains("assistant"), "Should have assistant events");

        assertTrue(action.getRawLogFile().exists(), "Raw log file should exist");
        assertTrue(action.getRawLogFile().length() > 0, "Raw log file should not be empty");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void codexRecording_producesCorrectEvents(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
                        "codex-recording",
                        new CodexAgentHandler(),
                        "codex-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue(cats.contains("thinking"), "Should have thinking");
        assertTrue(cats.contains("tool_call"), "Should have tool_call");
        assertTrue(cats.contains("tool_result"), "Should have tool_result");
        assertTrue(cats.contains("assistant"), "Should have assistant");
        assertEquals(6, events.size(), "Current Codex fixture should render 6 visible events");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void cursorAgentRecording_producesCorrectEvents(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
                        "cursor-recording",
                        new CursorAgentHandler(),
                        "cursor-agent-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue(cats.contains("system"), "Should have system");
        assertTrue(cats.contains("thinking"), "Should have thinking");
        assertTrue(cats.contains("tool_call"), "Should have tool_call");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void geminiCliRecording_producesCorrectEvents(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
                        "gemini-recording",
                        new GeminiCliAgentHandler(),
                        "gemini-cli-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats =
                events.stream()
                        .map(AiAgentLogParser.EventView::getCategory)
                        .collect(Collectors.toList());
        assertTrue(cats.contains("system"), "Should have system");
        assertTrue(cats.contains("user"), "Should have user");
        assertTrue(cats.contains("tool_call"), "Should have tool_call");
        assertTrue(cats.contains("assistant"), "Should have assistant");
        assertFalse(cats.contains("tool_result"), "Empty Gemini tool results should stay hidden");
        assertTrue(cats.contains("result"), "Gemini result event should now show with status text");
        assertEquals(6, events.size(), "Current Gemini fixture should render 6 visible events");
        long assistantCount =
                events.stream().filter(e -> "assistant".equals(e.getCategory())).count();
        assertEquals(1, assistantCount, "Gemini deltas should merge into one assistant event");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void openCodeRecording_showsCompletedToolOutputs(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
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
        assertEquals(2, toolResults.size(), "Should have 2 visible completed tool results");
        assertTrue(
                toolResults.stream().allMatch(e -> !e.getToolOutput().isEmpty()),
                "OpenCode tool result output should be rendered from nested state.output");
        assertEquals(
                4,
                action.getEvents().size(),
                "Current OpenCode fixture should render 4 visible events");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void errorRecording_buildSucceedsWithExitZero(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
                        "error-recording",
                        new ClaudeCodeAgentHandler(),
                        "error-conversation.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertEquals(Integer.valueOf(0), action.getExitCode());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void streamingRecording_handlesStreamEvents(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
                        "streaming-recording",
                        new ClaudeCodeAgentHandler(),
                        "claude-code-streaming.jsonl");

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertFalse(events.isEmpty(), "Should have events");

        boolean hasThinking = events.stream().anyMatch(e -> "thinking".equals(e.getCategory()));
        boolean hasAssistant = events.stream().anyMatch(e -> "assistant".equals(e.getCategory()));
        assertTrue(hasThinking, "Should have thinking from stream events");
        assertTrue(hasAssistant, "Should have assistant from stream events");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void actionMetadata_isPopulatedCorrectly(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                buildProjectWithFixture(
                        jenkins,
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
        assertNotNull(action.getStartedAt(), "Should have started timestamp");
        assertNotNull(action.getCompletedAt(), "Should have completed timestamp");
        assertNotNull(action.getExitCode(), "Should have exit code");
        assertEquals(Integer.valueOf(0), action.getExitCode());
        assertFalse(action.isLive(), "Build should not be live after completion");
    }
}
