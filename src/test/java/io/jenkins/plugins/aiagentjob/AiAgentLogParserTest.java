package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

public class AiAgentLogParserTest {

    private List<AiAgentLogParser.EventView> parseFixture(String name) throws IOException {
        File tempFile = File.createTempFile("fixture-", ".jsonl");
        tempFile.deleteOnExit();
        try (InputStream is = getClass().getResourceAsStream("fixtures/" + name)) {
            assertNotNull("Fixture not found: " + name, is);
            Files.copy(is, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return AiAgentLogParser.parse(tempFile);
    }

    private List<String> categories(List<AiAgentLogParser.EventView> events) {
        return events.stream()
                .map(AiAgentLogParser.EventView::getCategory)
                .collect(Collectors.toList());
    }

    // ======================== Claude Code Tests ========================

    @Test
    public void claudeCodeConversation_parsesAllEventTypes() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("claude-code-conversation.jsonl");
        assertFalse("Should have events", events.isEmpty());

        List<String> cats = categories(events);
        assertTrue("Should have system init", cats.contains("system"));
        assertTrue("Should have thinking", cats.contains("thinking"));
        assertTrue("Should have tool_call", cats.contains("tool_call"));
        assertTrue("Should have tool_result", cats.contains("tool_result"));
        assertTrue("Should have assistant", cats.contains("assistant"));
    }

    @Test
    public void claudeCodeConversation_detectsToolCallsWithNames() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("claude-code-conversation.jsonl");
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertFalse("Should have tool calls", toolCalls.isEmpty());
        boolean hasBash = toolCalls.stream().anyMatch(e -> e.getSummary().contains("Bash"));
        boolean hasRead = toolCalls.stream().anyMatch(e -> e.getSummary().contains("Read"));
        assertTrue("Should detect Bash tool call", hasBash);
        assertTrue("Should detect Read tool call", hasRead);
    }

    @Test
    public void claudeCodeConversation_capturesThinkingContent() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("claude-code-conversation.jsonl");
        List<AiAgentLogParser.EventView> thinking =
                events.stream()
                        .filter(e -> "thinking".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertFalse("Should have thinking events", thinking.isEmpty());
        assertTrue(
                "Thinking summary should mention analyzing",
                thinking.get(0).getSummary().contains("list the files"));
    }

    @Test
    public void claudeCodeStreaming_parsesStreamEvents() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("claude-code-streaming.jsonl");
        assertFalse("Should have events", events.isEmpty());

        List<String> cats = categories(events);
        assertTrue("Should have system events", cats.contains("system"));
        assertTrue("Should have thinking from stream", cats.contains("thinking"));
        assertTrue("Should have assistant from stream", cats.contains("assistant"));
    }

    @Test
    public void claudeCodeToolUseApproval_detectsMultipleToolCalls() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("claude-code-tool-use-approval.jsonl");
        long toolCallCount =
                events.stream().filter(e -> "tool_call".equals(e.getCategory())).count();
        assertTrue("Should have at least 2 tool calls (Edit and Bash)", toolCallCount >= 2);

        boolean hasEdit =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .anyMatch(e -> e.getSummary().contains("Edit"));
        boolean hasBash =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .anyMatch(e -> e.getSummary().contains("Bash"));
        assertTrue("Should detect Edit tool call", hasEdit);
        assertTrue("Should detect Bash tool call", hasBash);
    }

    // ======================== Codex Tests ========================

    @Test
    public void codexConversation_parsesCurrentTranscriptShape() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("codex-conversation.jsonl");
        assertFalse("Should have events", events.isEmpty());

        List<String> cats = categories(events);
        assertTrue("Should have thinking (reasoning)", cats.contains("thinking"));
        assertTrue("Should have tool_call (command_execution started)", cats.contains("tool_call"));
        assertTrue(
                "Should have tool_result (command_execution completed)",
                cats.contains("tool_result"));
        assertTrue("Should have assistant (agent_message)", cats.contains("assistant"));
        assertEquals("Current Codex fixture should keep only 6 visible events", 6, events.size());
    }

    @Test
    public void codexConversation_showsStartedCommandsWithCommandText() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("codex-conversation.jsonl");
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertEquals("Should have 2 visible command starts", 2, toolCalls.size());
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().contains("git remote -v")));
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().contains("rg --files")));
    }

    @Test
    public void codexConversation_hidesCompletionsWithoutOutput() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("codex-conversation.jsonl");
        long started = events.stream().filter(e -> "tool_call".equals(e.getCategory())).count();
        long completed = events.stream().filter(e -> "tool_result".equals(e.getCategory())).count();
        assertEquals("Fixture should keep both started commands visible", 2, started);
        assertEquals(
                "Only the command with aggregated output should render a result", 1, completed);
    }

    @Test
    public void codexConversation_completedCommandsShowAggregatedOutput() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("codex-conversation.jsonl");
        List<AiAgentLogParser.EventView> toolResults =
                events.stream()
                        .filter(e -> "tool_result".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertEquals("Should have exactly 1 visible tool result", 1, toolResults.size());
        assertTrue(toolResults.get(0).getToolOutput().contains("github.com-personal"));
    }

    // ======================== Cursor Agent Tests ========================

    @Test
    public void cursorAgentConversation_parsesAllTypes() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("cursor-agent-conversation.jsonl");
        assertFalse("Should have events", events.isEmpty());

        List<String> cats = categories(events);
        assertTrue("Should have system", cats.contains("system"));
        assertTrue("Should have thinking", cats.contains("thinking"));
        assertTrue("Should have assistant", cats.contains("assistant"));
        assertTrue("Should have tool_call", cats.contains("tool_call"));
        assertTrue("Should have tool_result", cats.contains("tool_result"));
    }

    @Test
    public void cursorAgentConversation_detectsShellAndReadTools() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("cursor-agent-conversation.jsonl");
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertTrue("Should have at least 2 tool calls", toolCalls.size() >= 2);
        boolean hasShell =
                toolCalls.stream().anyMatch(e -> e.getSummary().toLowerCase().contains("shell"));
        boolean hasRead =
                toolCalls.stream().anyMatch(e -> e.getSummary().toLowerCase().contains("read"));
        assertTrue("Should detect shell tool", hasShell);
        assertTrue("Should detect read tool", hasRead);
    }

    // ======================== Gemini CLI Tests ========================

    @Test
    public void geminiCliConversation_parsesCurrentTranscriptShape() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("gemini-cli-conversation.jsonl");
        assertFalse("Should have events", events.isEmpty());

        List<String> cats = categories(events);
        assertTrue("Should have system", cats.contains("system"));
        assertTrue("Should have assistant", cats.contains("assistant"));
        assertTrue("Should have tool_call", cats.contains("tool_call"));
        assertTrue("Should have user prompt", cats.contains("user"));
        assertFalse("Empty Gemini tool results should stay hidden", cats.contains("tool_result"));
        assertFalse("Stats-only Gemini result event should stay hidden", cats.contains("result"));
        assertEquals("Current Gemini fixture should keep 5 visible events", 5, events.size());
        long assistantCount =
                events.stream().filter(e -> "assistant".equals(e.getCategory())).count();
        assertEquals("Gemini deltas should merge into one assistant message", 1, assistantCount);
        AiAgentLogParser.EventView assistant =
                events.stream()
                        .filter(e -> "assistant".equals(e.getCategory()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing assistant event"));
        assertTrue(
                "Merged assistant message should contain both delta fragments",
                assistant.getContent().contains("Jenkins AI Agent Plugin"));
        assertTrue(
                "Merged assistant message should contain later delta fragments",
                assistant.getContent().contains("approval gates"));
    }

    @Test
    public void geminiCliConversation_showsFileInputsAndHidesEmptyResults() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("gemini-cli-conversation.jsonl");
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertEquals("Should have 2 tool calls", 2, toolCalls.size());
        assertTrue(
                "Gemini tool calls should show extracted input",
                toolCalls.stream().allMatch(e -> !e.getToolInput().isEmpty()));
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().endsWith("/README.md")));
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().endsWith("/pom.xml")));
        assertEquals(
                "Empty Gemini tool_result entries should be hidden",
                0,
                events.stream().filter(e -> "tool_result".equals(e.getCategory())).count());
    }

    // ======================== OpenCode Tests ========================

    @Test
    public void openCodeConversation_parsesCurrentTranscriptShape() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("opencode-conversation.jsonl");
        assertFalse("Should have events", events.isEmpty());

        List<String> cats = categories(events);
        assertTrue("Should have assistant", cats.contains("assistant"));
        assertTrue("Should have tool_result", cats.contains("tool_result"));
        assertFalse("Step markers should stay hidden", cats.contains("system"));
        assertFalse(
                "Completed tool parts should render as results, not calls",
                cats.contains("tool_call"));
        assertEquals("Current OpenCode fixture should keep 3 visible events", 3, events.size());
    }

    @Test
    public void openCodeConversation_showsToolOutputsAndAssistantText() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("opencode-conversation.jsonl");
        assertFalse("Should have events", events.isEmpty());

        List<AiAgentLogParser.EventView> toolResults =
                events.stream()
                        .filter(e -> "tool_result".equals(e.getCategory()))
                        .collect(Collectors.toList());
        assertEquals("Should have 2 completed tool results", 2, toolResults.size());
        assertTrue(
                "OpenCode tool results should show nested state output",
                toolResults.stream().allMatch(e -> !e.getToolOutput().isEmpty()));

        AiAgentLogParser.EventView assistant =
                events.stream()
                        .filter(e -> "assistant".equals(e.getCategory()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing assistant event"));
        assertTrue(assistant.getContent().contains("AI Agent Job"));
    }

    // ======================== Error Handling Tests ========================

    @Test
    public void errorConversation_detectsErrors() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("error-conversation.jsonl");
        assertFalse("Should have events", events.isEmpty());

        boolean hasSystem = events.stream().anyMatch(e -> "system".equals(e.getCategory()));
        assertTrue("Should have system/result events", hasSystem);
    }

    // ======================== Edge Case Tests ========================

    @Test
    public void parseLine_handlesEmptyString() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, "");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesNull() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, null);
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesPlainText() {
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, "This is just plain text output");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesInvalidJson() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, "{invalid json}");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesJsonArray() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, "[1,2,3]");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesMinimalThinkingEvent() {
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(
                        1, "{\"type\":\"thinking\",\"text\":\"analyzing the problem\"}");
        assertEquals("thinking", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("analyzing"));
    }

    @Test
    public void parseLine_handlesToolCallWithNestedInput() {
        String json =
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"Bash\",\"input\":{\"command\":\"echo hello\"}}]}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.isToolCall());
        assertEquals("tu1", line.getToolCallIdOrGenerated());
        assertEquals("Bash", line.getToolName());
    }

    @Test
    public void parseLine_handlesClaudeUserToolResult() {
        String json =
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":\"total 24\\nREADME.md\",\"is_error\":false}]}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_result", line.toEventView().getCategory());
        assertEquals("total 24\nREADME.md", line.toEventView().getToolOutput());
        assertEquals("tu1", line.getToolCallIdOrGenerated());
    }

    @Test
    public void parseLine_skipsEmptyClaudeUserToolResultWrapper() {
        String json =
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":[{\"type\":\"tool_reference\",\"tool_name\":\"Read\"}],\"is_error\":false}]}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertTrue("Empty tool_result wrapper should be ignored", line.toEventView().isEmpty());
    }

    @Test
    public void parseLine_handlesCodexReasoningItem() {
        String json =
                "{\"type\":\"item.started\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\",\"status\":\"in_progress\",\"text\":\"thinking deeply\"}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("thinking", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesCodexCommandExecution() {
        String json =
                "{\"type\":\"item.started\",\"item\":{\"id\":\"cmd1\",\"type\":\"command_execution\",\"status\":\"in_progress\",\"command\":\"ls -la\"}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.isToolCall());
        assertEquals("ls -la", line.toEventView().getToolInput());
    }

    @Test
    public void parseLine_handlesCodexCommandExecutionCompletedWithAggregatedOutput() {
        String json =
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"cmd1\",\"type\":\"command_execution\",\"status\":\"completed\",\"command\":\"ls -la\",\"aggregated_output\":\"README.md\\npom.xml\"}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_result", line.toEventView().getCategory());
        assertEquals("README.md\npom.xml", line.toEventView().getToolOutput());
    }

    @Test
    public void parseLine_skipsCodexStructuralEventsWithoutDisplayableText() {
        AiAgentLogParser.ParsedLine threadStarted =
                AiAgentLogParser.parseLine(1, "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}");
        AiAgentLogParser.ParsedLine turnStarted =
                AiAgentLogParser.parseLine(2, "{\"type\":\"turn.started\"}");
        assertTrue(threadStarted.toEventView().isEmpty());
        assertTrue(turnStarted.toEventView().isEmpty());
    }

    @Test
    public void parseLine_skipsEmptyCodexAgentMessage() {
        String json =
                "{\"type\":\"item.started\",\"item\":{\"id\":\"msg1\",\"type\":\"agent_message\",\"status\":\"in_progress\",\"text\":\"\"}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertTrue(line.toEventView().isEmpty());
    }

    @Test
    public void parseLine_handlesGeminiToolUseWithParameters() {
        String json =
                "{\"type\":\"tool_use\",\"tool_name\":\"read_file\",\"tool_id\":\"tool-1\",\"parameters\":{\"file_path\":\"/tmp/README.md\"}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.toEventView().getToolInput().contains("/tmp/README.md"));
        assertEquals("tool-1", line.getToolCallIdOrGenerated());
    }

    @Test
    public void parseLine_skipsEmptyGeminiToolResult() {
        String json =
                "{\"type\":\"tool_result\",\"tool_id\":\"tool-1\",\"status\":\"success\",\"output\":\"\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertTrue(line.toEventView().isEmpty());
    }

    @Test
    public void parseLine_handlesGeminiInitWithModel() {
        String json =
                "{\"type\":\"init\",\"timestamp\":\"2026-03-07T03:24:37.343Z\",\"session_id\":\"s1\",\"model\":\"gemini-3.1-pro-preview\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("system", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("gemini-3.1-pro-preview"));
    }

    @Test
    public void parseLine_handlesOpenCodeToolPartWithCompletedOutput() {
        String json =
                "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"callID\":\"call_1\",\"tool\":\"glob\",\"state\":{\"status\":\"completed\",\"input\":{\"pattern\":\"*\"},\"output\":\"/tmp/a\\n/tmp/b\"}}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_result", line.toEventView().getCategory());
        assertEquals("/tmp/a\n/tmp/b", line.toEventView().getToolOutput());
        assertEquals("call_1", line.getToolCallIdOrGenerated());
    }

    @Test
    public void parseLine_handlesOpenCodeTextPartAsAssistantMessage() {
        String json =
                "{\"type\":\"text\",\"part\":{\"type\":\"text\",\"text\":\"This is a Jenkins plugin.\"}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("assistant", line.toEventView().getCategory());
        assertEquals("This is a Jenkins plugin.", line.toEventView().getContent());
    }

    @Test
    public void parseLine_handlesCursorToolCallStarted() {
        String json =
                "{\"type\":\"tool_call\",\"subtype\":\"started\",\"call_id\":\"c1\",\"tool_call\":{\"shellToolCall\":{\"args\":{\"command\":\"ls\"}}}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.isToolCall());
        assertEquals("c1", line.getToolCallIdOrGenerated());
    }

    @Test
    public void parseLine_handlesCursorToolCallCompleted() {
        String json =
                "{\"type\":\"tool_call\",\"subtype\":\"completed\",\"call_id\":\"c1\",\"tool_call\":{\"shellToolCall\":{\"args\":{\"command\":\"ls\"},\"result\":{\"success\":{\"stdout\":\"file.txt\",\"exitCode\":0}}}}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_result", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesClaudeSystemInit() {
        String json =
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\",\"model\":\"claude-sonnet-4\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("system", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("claude-sonnet-4"));
    }

    @Test
    public void parseLine_skipsEmptyResultWithoutDisplayText() {
        String json =
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"duration_ms\":5000}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertTrue(line.toEventView().isEmpty());
    }

    @Test
    public void parseLine_handlesResultWithDisplayText() {
        String json =
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"duration_ms\":5000,\"result\":\"Build completed successfully.\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("result", line.toEventView().getCategory());
        assertTrue(line.toEventView().getLabel().contains("Result"));
        assertTrue(line.toEventView().getLabel().contains("5.0s"));
        assertEquals("Build completed successfully.", line.toEventView().getContent());
    }

    @Test
    public void parseLine_handlesStreamEventMessageStart() {
        String json =
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4\",\"content\":[]}}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("system", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("claude-sonnet-4"));
    }

    @Test
    public void parseLine_handlesStreamEventThinkingDelta() {
        String json =
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"considering options\"}}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("thinking", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesStreamEventTextDelta() {
        String json =
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Here is the result.\"}}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("assistant", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_handlesErrorWithErrorField() {
        String json = "{\"type\":\"error\",\"error\":\"Rate limit exceeded\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("error", line.toEventView().getCategory());
    }

    @Test
    public void parseLine_generatesToolCallIdWhenMissing() {
        String json = "{\"type\":\"tool_call\",\"tool_name\":\"bash\",\"text\":\"ls\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(42, json);
        assertEquals("tool-call-42", line.getToolCallIdOrGenerated());
    }

    @Test
    public void eventView_categoryLabelsAreReadable() {
        AiAgentLogParser.EventView view =
                new AiAgentLogParser.EventView(
                        1,
                        "tool_call",
                        "Bash",
                        "",
                        "ls -la",
                        "",
                        "{}",
                        java.time.Instant.now(),
                        false);
        assertEquals("TOOL CALL", view.getCategoryLabel());
    }

    @Test
    public void eventView_inlineContentForAssistantAndResult() {
        AiAgentLogParser.EventView error =
                new AiAgentLogParser.EventView(
                        1,
                        "error",
                        "Error",
                        "Something failed",
                        "",
                        "",
                        "{}",
                        java.time.Instant.now(),
                        false);
        assertTrue(error.isInlineContent());

        AiAgentLogParser.EventView toolCall =
                new AiAgentLogParser.EventView(
                        2, "tool_call", "Bash", "", "ls", "", "{}", java.time.Instant.now(), false);
        assertTrue(toolCall.isToolEvent());
        assertFalse(toolCall.isInlineContent());

        AiAgentLogParser.EventView assistant =
                new AiAgentLogParser.EventView(
                        3,
                        "assistant",
                        "Assistant",
                        "Hello",
                        "",
                        "",
                        "{}",
                        java.time.Instant.now(),
                        false);
        assertTrue(assistant.isInlineContent());
    }

    @Test
    public void parse_returnsEmptyForNullFile() throws IOException {
        List<AiAgentLogParser.EventView> events = AiAgentLogParser.parse(null);
        assertTrue(events.isEmpty());
    }

    @Test
    public void parse_returnsEmptyForNonexistentFile() throws IOException {
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(new File("/nonexistent/path.jsonl"));
        assertTrue(events.isEmpty());
    }

    // ======================== Full Fixture Event Count Tests
    // ========================

    @Test
    public void claudeCodeConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("claude-code-conversation.jsonl");
        assertTrue("Should have at least 5 events for a full conversation", events.size() >= 5);
    }

    @Test
    public void codexConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("codex-conversation.jsonl");
        assertEquals("Current Codex fixture should produce 6 visible events", 6, events.size());
    }

    @Test
    public void cursorConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("cursor-agent-conversation.jsonl");
        assertTrue("Should have at least 6 events", events.size() >= 6);
    }

    @Test
    public void geminiConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("gemini-cli-conversation.jsonl");
        assertEquals("Current Gemini fixture should produce 5 visible events", 5, events.size());
    }

    @Test
    public void openCodeConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("opencode-conversation.jsonl");
        assertEquals("Current OpenCode fixture should produce 3 visible events", 3, events.size());
    }

    // ======================== Markdown rendering ========================

    @Test
    public void contentHtml_convertsBasicMarkdown() {
        AiAgentLogParser.EventView ev =
                new AiAgentLogParser.EventView(
                        1,
                        "assistant",
                        "Assistant",
                        "Hello **world**!",
                        "",
                        "",
                        "{}",
                        java.time.Instant.now(),
                        false);
        String html = ev.getContentHtml();
        assertTrue("Should contain <strong>", html.contains("<strong>world</strong>"));
    }

    @Test
    public void contentHtml_convertsCodeBlocks() {
        String md = "Here:\n```\nfoo()\n```\nDone.";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue("Should contain <pre><code>", html.contains("<pre><code>"));
        assertTrue("Should contain foo()", html.contains("foo()"));
    }

    @Test
    public void contentHtml_convertsBulletLists() {
        String md = "Items:\n- one\n- two\n- three";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue("Should contain <ul>", html.contains("<ul>"));
        assertTrue("Should contain <li>one</li>", html.contains("<li>one</li>"));
    }

    @Test
    public void contentHtml_convertsHeaders() {
        String md = "# Title\n## Subtitle";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue("Should contain strong for title", html.contains("<strong>Title</strong>"));
        assertTrue(
                "Should contain strong for subtitle", html.contains("<strong>Subtitle</strong>"));
    }

    @Test
    public void contentHtml_escapesHtmlInContent() {
        String md = "Use <div> tags & \"quotes\"";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue("Should escape <", html.contains("&lt;div&gt;"));
        assertTrue("Should escape &", html.contains("&amp;"));
    }

    @Test
    public void contentHtml_handlesEmptyString() {
        assertEquals("", AiAgentLogParser.EventView.markdownToHtml(""));
        assertEquals("", AiAgentLogParser.EventView.markdownToHtml(null));
    }

    @Test
    public void contentHtml_convertsInlineCode() {
        String md = "Run `echo hello` now";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue("Should contain <code>", html.contains("<code>echo hello</code>"));
    }

    // ======================== Result deduplication ========================

    @Test
    public void parse_deduplicatesResultThatRepeatsAssistant() throws IOException {
        File temp = File.createTempFile("dedup-", ".jsonl");
        temp.deleteOnExit();
        java.nio.file.Files.write(
                temp.toPath(),
                java.util.Arrays.asList(
                        "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"The answer is 42.\"}]}}",
                        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                                + "\"duration_ms\":5000,\"result\":\"Some preamble.The answer is 42.\"}"));
        List<AiAgentLogParser.EventView> events = AiAgentLogParser.parse(temp);

        AiAgentLogParser.EventView assistant =
                events.stream()
                        .filter(e -> "assistant".equals(e.getCategory()))
                        .findFirst()
                        .orElse(null);
        assertNotNull("Should have assistant event", assistant);
        assertEquals("The answer is 42.", assistant.getContent());

        AiAgentLogParser.EventView result =
                events.stream()
                        .filter(e -> "result".equals(e.getCategory()))
                        .findFirst()
                        .orElse(null);
        assertNotNull("Should have result event", result);
        assertTrue("Result content should be empty (deduplicated)", result.getContent().isEmpty());
    }

    @Test
    public void parse_keepsResultWhenDifferentFromAssistant() throws IOException {
        File temp = File.createTempFile("nodedup-", ".jsonl");
        temp.deleteOnExit();
        java.nio.file.Files.write(
                temp.toPath(),
                java.util.Arrays.asList(
                        "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":"
                                + "[{\"type\":\"text\",\"text\":\"Hello!\"}]}}",
                        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                                + "\"duration_ms\":1000,\"result\":\"Completely different result.\"}"));
        List<AiAgentLogParser.EventView> events = AiAgentLogParser.parse(temp);

        AiAgentLogParser.EventView result =
                events.stream()
                        .filter(e -> "result".equals(e.getCategory()))
                        .findFirst()
                        .orElse(null);
        assertNotNull("Should have result event", result);
        assertEquals("Completely different result.", result.getContent());
    }
}
