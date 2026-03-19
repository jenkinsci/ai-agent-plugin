package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeLogFormat;
import io.jenkins.plugins.aiagentjob.codex.CodexLogFormat;
import io.jenkins.plugins.aiagentjob.cursor.CursorLogFormat;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeLogFormat;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

class AiAgentLogParserTest {

    private List<AiAgentLogParser.EventView> parseFixture(String name) throws IOException {
        return parseFixture(name, null);
    }

    private List<AiAgentLogParser.EventView> parseFixture(String name, AiAgentLogFormat format)
            throws IOException {
        File tempFile = File.createTempFile("fixture-", ".jsonl");
        tempFile.deleteOnExit();
        try (InputStream is = getClass().getResourceAsStream("fixtures/" + name)) {
            assertNotNull(is, "Fixture not found: " + name);
            Files.copy(is, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return AiAgentLogParser.parse(tempFile, format);
    }

    private List<String> categories(List<AiAgentLogParser.EventView> events) {
        return events.stream()
                .map(AiAgentLogParser.EventView::getCategory)
                .collect(Collectors.toList());
    }

    // ======================== Claude Code Tests ========================

    @Test
    void claudeCodeConversation_parsesAllEventTypes() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("claude-code-conversation.jsonl", ClaudeCodeLogFormat.INSTANCE);
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats = categories(events);
        assertTrue(cats.contains("system"), "Should have system init");
        assertTrue(cats.contains("thinking"), "Should have thinking");
        assertTrue(cats.contains("tool_call"), "Should have tool_call");
        assertTrue(cats.contains("tool_result"), "Should have tool_result");
        assertTrue(cats.contains("assistant"), "Should have assistant");
    }

    @Test
    void claudeCodeConversation_detectsToolCallsWithNames() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("claude-code-conversation.jsonl", ClaudeCodeLogFormat.INSTANCE);
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertFalse(toolCalls.isEmpty(), "Should have tool calls");
        boolean hasBash = toolCalls.stream().anyMatch(e -> e.getSummary().contains("Bash"));
        boolean hasRead = toolCalls.stream().anyMatch(e -> e.getSummary().contains("Read"));
        assertTrue(hasBash, "Should detect Bash tool call");
        assertTrue(hasRead, "Should detect Read tool call");
    }

    @Test
    void claudeCodeConversation_capturesThinkingContent() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("claude-code-conversation.jsonl", ClaudeCodeLogFormat.INSTANCE);
        List<AiAgentLogParser.EventView> thinking =
                events.stream()
                        .filter(e -> "thinking".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertFalse(thinking.isEmpty(), "Should have thinking events");
        assertTrue(
                thinking.get(0).getSummary().contains("list the files"),
                "Thinking summary should mention analyzing");
    }

    @Test
    void claudeCodeStreaming_parsesStreamEvents() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("claude-code-streaming.jsonl", ClaudeCodeLogFormat.INSTANCE);
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats = categories(events);
        assertTrue(cats.contains("system"), "Should have system events");
        assertTrue(cats.contains("thinking"), "Should have thinking from stream");
        assertTrue(cats.contains("assistant"), "Should have assistant from stream");
    }

    @Test
    void claudeCodeToolUseApproval_detectsMultipleToolCalls() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("claude-code-tool-use-approval.jsonl", ClaudeCodeLogFormat.INSTANCE);
        long toolCallCount =
                events.stream().filter(e -> "tool_call".equals(e.getCategory())).count();
        assertTrue(toolCallCount >= 2, "Should have at least 2 tool calls (Edit and Bash)");

        boolean hasEdit =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .anyMatch(e -> e.getSummary().contains("Edit"));
        boolean hasBash =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .anyMatch(e -> e.getSummary().contains("Bash"));
        assertTrue(hasEdit, "Should detect Edit tool call");
        assertTrue(hasBash, "Should detect Bash tool call");
    }

    // ======================== Codex Tests ========================

    @Test
    void codexConversation_parsesCurrentTranscriptShape() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("codex-conversation.jsonl", CodexLogFormat.INSTANCE);
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats = categories(events);
        assertTrue(cats.contains("thinking"), "Should have thinking (reasoning)");
        assertTrue(cats.contains("tool_call"), "Should have tool_call (command_execution started)");
        assertTrue(
                cats.contains("tool_result"),
                "Should have tool_result (command_execution completed)");
        assertTrue(cats.contains("assistant"), "Should have assistant (agent_message)");
        assertEquals(6, events.size(), "Current Codex fixture should keep only 6 visible events");
    }

    @Test
    void codexConversation_showsStartedCommandsWithCommandText() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("codex-conversation.jsonl", CodexLogFormat.INSTANCE);
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertEquals(2, toolCalls.size(), "Should have 2 visible command starts");
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().contains("git remote -v")));
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().contains("rg --files")));
    }

    @Test
    void codexConversation_hidesCompletionsWithoutOutput() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("codex-conversation.jsonl", CodexLogFormat.INSTANCE);
        long started = events.stream().filter(e -> "tool_call".equals(e.getCategory())).count();
        long completed = events.stream().filter(e -> "tool_result".equals(e.getCategory())).count();
        assertEquals(2, started, "Fixture should keep both started commands visible");
        assertEquals(
                1, completed, "Only the command with aggregated output should render a result");
    }

    @Test
    void codexConversation_completedCommandsShowAggregatedOutput() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("codex-conversation.jsonl", CodexLogFormat.INSTANCE);
        List<AiAgentLogParser.EventView> toolResults =
                events.stream()
                        .filter(e -> "tool_result".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertEquals(1, toolResults.size(), "Should have exactly 1 visible tool result");
        assertTrue(toolResults.get(0).getToolOutput().contains("github.com-personal"));
    }

    // ======================== Cursor Agent Tests ========================

    @Test
    void cursorAgentConversation_parsesAllTypes() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("cursor-agent-conversation.jsonl", CursorLogFormat.INSTANCE);
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats = categories(events);
        assertTrue(cats.contains("system"), "Should have system");
        assertTrue(cats.contains("thinking"), "Should have thinking");
        assertTrue(cats.contains("assistant"), "Should have assistant");
        assertTrue(cats.contains("tool_call"), "Should have tool_call");
        assertTrue(cats.contains("tool_result"), "Should have tool_result");
    }

    @Test
    void cursorAgentConversation_detectsShellAndReadTools() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("cursor-agent-conversation.jsonl", CursorLogFormat.INSTANCE);
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertTrue(toolCalls.size() >= 2, "Should have at least 2 tool calls");
        boolean hasShell =
                toolCalls.stream().anyMatch(e -> e.getSummary().toLowerCase().contains("shell"));
        boolean hasRead =
                toolCalls.stream().anyMatch(e -> e.getSummary().toLowerCase().contains("read"));
        assertTrue(hasShell, "Should detect shell tool");
        assertTrue(hasRead, "Should detect read tool");
    }

    // ======================== Gemini CLI Tests ========================

    @Test
    void geminiCliConversation_parsesCurrentTranscriptShape() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("gemini-cli-conversation.jsonl", ClaudeCodeLogFormat.INSTANCE);
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats = categories(events);
        assertTrue(cats.contains("system"), "Should have system");
        assertTrue(cats.contains("assistant"), "Should have assistant");
        assertTrue(cats.contains("tool_call"), "Should have tool_call");
        assertTrue(cats.contains("user"), "Should have user prompt");
        assertFalse(cats.contains("tool_result"), "Empty Gemini tool results should stay hidden");
        assertTrue(cats.contains("result"), "Gemini result event should now show with status text");
        assertEquals(6, events.size(), "Current Gemini fixture should keep 6 visible events");
        long assistantCount =
                events.stream().filter(e -> "assistant".equals(e.getCategory())).count();
        assertEquals(1, assistantCount, "Gemini deltas should merge into one assistant message");
        AiAgentLogParser.EventView assistant =
                events.stream()
                        .filter(e -> "assistant".equals(e.getCategory()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing assistant event"));
        assertTrue(
                assistant.getContent().contains("Jenkins AI Agent Plugin"),
                "Merged assistant message should contain both delta fragments");
        assertTrue(
                assistant.getContent().contains("approval gates"),
                "Merged assistant message should contain later delta fragments");
    }

    @Test
    void geminiCliConversation_showsFileInputsAndHidesEmptyResults() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("gemini-cli-conversation.jsonl", ClaudeCodeLogFormat.INSTANCE);
        List<AiAgentLogParser.EventView> toolCalls =
                events.stream()
                        .filter(e -> "tool_call".equals(e.getCategory()))
                        .collect(Collectors.toList());

        assertEquals(2, toolCalls.size(), "Should have 2 tool calls");
        assertTrue(
                toolCalls.stream().allMatch(e -> !e.getToolInput().isEmpty()),
                "Gemini tool calls should show extracted input");
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().endsWith("/README.md")));
        assertTrue(toolCalls.stream().anyMatch(e -> e.getToolInput().endsWith("/pom.xml")));
        assertEquals(
                0,
                events.stream().filter(e -> "tool_result".equals(e.getCategory())).count(),
                "Empty Gemini tool_result entries should be hidden");
    }

    // ======================== OpenCode Tests ========================

    @Test
    void openCodeConversation_parsesCurrentTranscriptShape() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("opencode-conversation.jsonl", OpenCodeLogFormat.INSTANCE);
        assertFalse(events.isEmpty(), "Should have events");

        List<String> cats = categories(events);
        assertTrue(cats.contains("assistant"), "Should have assistant");
        assertTrue(cats.contains("tool_result"), "Should have tool_result");
        assertFalse(cats.contains("system"), "Step markers should stay hidden");
        assertFalse(
                cats.contains("tool_call"),
                "Completed tool parts should render as results, not calls");
        assertTrue(cats.contains("result"), "OpenCode step_finish should produce result event");
        assertEquals(4, events.size(), "Current OpenCode fixture should keep 4 visible events");
    }

    @Test
    void openCodeConversation_showsToolOutputsAndAssistantText() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("opencode-conversation.jsonl", OpenCodeLogFormat.INSTANCE);
        assertFalse(events.isEmpty(), "Should have events");

        List<AiAgentLogParser.EventView> toolResults =
                events.stream()
                        .filter(e -> "tool_result".equals(e.getCategory()))
                        .collect(Collectors.toList());
        assertEquals(2, toolResults.size(), "Should have 2 completed tool results");
        assertTrue(
                toolResults.stream().allMatch(e -> !e.getToolOutput().isEmpty()),
                "OpenCode tool results should show nested state output");

        AiAgentLogParser.EventView assistant =
                events.stream()
                        .filter(e -> "assistant".equals(e.getCategory()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing assistant event"));
        assertTrue(assistant.getContent().contains("AI Agent"));
    }

    // ======================== Error Handling Tests ========================

    @Test
    void errorConversation_detectsErrors() throws IOException {
        List<AiAgentLogParser.EventView> events = parseFixture("error-conversation.jsonl");
        assertFalse(events.isEmpty(), "Should have events");

        boolean hasSystem = events.stream().anyMatch(e -> "system".equals(e.getCategory()));
        assertTrue(hasSystem, "Should have system/result events");
    }

    // ======================== Edge Case Tests ========================

    @Test
    void parseLine_handlesEmptyString() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, "");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesNull() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, null);
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesPlainText() {
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, "This is just plain text output");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesInvalidJson() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, "{invalid json}");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesJsonArray() {
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, "[1,2,3]");
        assertEquals("raw", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesMinimalThinkingEvent() {
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(
                        1,
                        "{\"type\":\"thinking\",\"text\":\"analyzing the problem\"}",
                        CursorLogFormat.INSTANCE);
        assertEquals("thinking", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("analyzing"));
    }

    @Test
    void parseLine_handlesToolCallWithNestedInput() {
        String json =
                "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"Bash\",\"input\":{\"command\":\"echo hello\"}}]}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, ClaudeCodeLogFormat.INSTANCE);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.isToolCall());
        assertEquals("tu1", line.getToolCallIdOrGenerated());
        assertEquals("Bash", line.getToolName());
    }

    @Test
    void parseLine_handlesClaudeUserToolResult() {
        String json =
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":\"total 24\\nREADME.md\",\"is_error\":false}]}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, ClaudeCodeLogFormat.INSTANCE);
        assertEquals("tool_result", line.toEventView().getCategory());
        assertEquals("total 24\nREADME.md", line.toEventView().getToolOutput());
        assertEquals("tu1", line.getToolCallIdOrGenerated());
    }

    @Test
    void parseLine_skipsEmptyClaudeUserToolResultWrapper() {
        String json =
                "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu1\",\"content\":[{\"type\":\"tool_reference\",\"tool_name\":\"Read\"}],\"is_error\":false}]}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, ClaudeCodeLogFormat.INSTANCE);
        assertTrue(line.toEventView().isEmpty(), "Empty tool_result wrapper should be ignored");
    }

    @Test
    void parseLine_handlesCodexReasoningItem() {
        String json =
                "{\"type\":\"item.started\",\"item\":{\"id\":\"r1\",\"type\":\"reasoning\",\"status\":\"in_progress\",\"text\":\"thinking deeply\"}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, CodexLogFormat.INSTANCE);
        assertEquals("thinking", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesCodexCommandExecution() {
        String json =
                "{\"type\":\"item.started\",\"item\":{\"id\":\"cmd1\",\"type\":\"command_execution\",\"status\":\"in_progress\",\"command\":\"ls -la\"}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, CodexLogFormat.INSTANCE);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.isToolCall());
        assertEquals("ls -la", line.toEventView().getToolInput());
    }

    @Test
    void parseLine_handlesCodexCommandExecutionCompletedWithAggregatedOutput() {
        String json =
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"cmd1\",\"type\":\"command_execution\",\"status\":\"completed\",\"command\":\"ls -la\",\"aggregated_output\":\"README.md\\npom.xml\"}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, CodexLogFormat.INSTANCE);
        assertEquals("tool_result", line.toEventView().getCategory());
        assertEquals("README.md\npom.xml", line.toEventView().getToolOutput());
    }

    @Test
    void parseLine_skipsCodexStructuralEventsWithoutDisplayableText() {
        AiAgentLogParser.ParsedLine threadStarted =
                AiAgentLogParser.parseLine(
                        1,
                        "{\"type\":\"thread.started\",\"thread_id\":\"t1\"}",
                        CodexLogFormat.INSTANCE);
        AiAgentLogParser.ParsedLine turnStarted =
                AiAgentLogParser.parseLine(
                        2, "{\"type\":\"turn.started\"}", CodexLogFormat.INSTANCE);
        assertTrue(threadStarted.toEventView().isEmpty());
        assertTrue(turnStarted.toEventView().isEmpty());
    }

    @Test
    void parseLine_skipsEmptyCodexAgentMessage() {
        String json =
                "{\"type\":\"item.started\",\"item\":{\"id\":\"msg1\",\"type\":\"agent_message\",\"status\":\"in_progress\",\"text\":\"\"}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, CodexLogFormat.INSTANCE);
        assertTrue(line.toEventView().isEmpty());
    }

    @Test
    void parseLine_handlesGeminiToolUseWithParameters() {
        String json =
                "{\"type\":\"tool_use\",\"tool_name\":\"read_file\",\"tool_id\":\"tool-1\",\"parameters\":{\"file_path\":\"/tmp/README.md\"}}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.toEventView().getToolInput().contains("/tmp/README.md"));
        assertEquals("tool-1", line.getToolCallIdOrGenerated());
    }

    @Test
    void parseLine_skipsEmptyGeminiToolResult() {
        String json =
                "{\"type\":\"tool_result\",\"tool_id\":\"tool-1\",\"status\":\"success\",\"output\":\"\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertTrue(line.toEventView().isEmpty());
    }

    @Test
    void parseLine_handlesGeminiInitWithModel() {
        String json =
                "{\"type\":\"init\",\"timestamp\":\"2026-03-07T03:24:37.343Z\",\"session_id\":\"s1\",\"model\":\"gemini-3.1-pro-preview\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("system", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("gemini-3.1-pro-preview"));
    }

    @Test
    void parseLine_handlesOpenCodeToolPartWithCompletedOutput() {
        String json =
                "{\"type\":\"tool_use\",\"part\":{\"type\":\"tool\",\"callID\":\"call_1\",\"tool\":\"glob\",\"state\":{\"status\":\"completed\",\"input\":{\"pattern\":\"*\"},\"output\":\"/tmp/a\\n/tmp/b\"}}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, OpenCodeLogFormat.INSTANCE);
        assertEquals("tool_result", line.toEventView().getCategory());
        assertEquals("/tmp/a\n/tmp/b", line.toEventView().getToolOutput());
        assertEquals("call_1", line.getToolCallIdOrGenerated());
    }

    @Test
    void parseLine_handlesOpenCodeTextPartAsAssistantMessage() {
        String json =
                "{\"type\":\"text\",\"part\":{\"type\":\"text\",\"text\":\"This is a Jenkins plugin.\"}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, OpenCodeLogFormat.INSTANCE);
        assertEquals("assistant", line.toEventView().getCategory());
        assertEquals("This is a Jenkins plugin.", line.toEventView().getContent());
    }

    @Test
    void parseLine_handlesCursorToolCallStarted() {
        String json =
                "{\"type\":\"tool_call\",\"subtype\":\"started\",\"call_id\":\"c1\",\"tool_call\":{\"shellToolCall\":{\"args\":{\"command\":\"ls\"}}}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, CursorLogFormat.INSTANCE);
        assertEquals("tool_call", line.toEventView().getCategory());
        assertTrue(line.isToolCall());
        assertEquals("c1", line.getToolCallIdOrGenerated());
    }

    @Test
    void parseLine_handlesCursorToolCallCompleted() {
        String json =
                "{\"type\":\"tool_call\",\"subtype\":\"completed\",\"call_id\":\"c1\",\"tool_call\":{\"shellToolCall\":{\"args\":{\"command\":\"ls\"},\"result\":{\"success\":{\"stdout\":\"file.txt\",\"exitCode\":0}}}}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, CursorLogFormat.INSTANCE);
        assertEquals("tool_result", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesClaudeSystemInit() {
        String json =
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s1\",\"model\":\"claude-sonnet-4\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("system", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("claude-sonnet-4"));
    }

    @Test
    void parseLine_skipsEmptyResultWithoutDisplayText() {
        // No result, no error, no status, no subtype → truly empty → raw
        String json = "{\"type\":\"result\",\"is_error\":false,\"duration_ms\":5000}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertTrue(line.toEventView().isEmpty());
    }

    @Test
    void parseLine_handlesResultWithDisplayText() {
        Locale.setDefault(Locale.US);
        String json =
                "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"duration_ms\":5000,\"result\":\"Build completed successfully.\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("result", line.toEventView().getCategory());
        assertTrue(line.toEventView().getLabel().contains("Result"));
        assertTrue(line.toEventView().getLabel().contains("5.0s"));
        assertEquals("Build completed successfully.", line.toEventView().getContent());
    }

    @Test
    void parseLine_handlesStreamEventMessageStart() {
        String json =
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\",\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4\",\"content\":[]}}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, ClaudeCodeLogFormat.INSTANCE);
        assertEquals("system", line.toEventView().getCategory());
        assertTrue(line.toEventView().getSummary().contains("claude-sonnet-4"));
    }

    @Test
    void parseLine_handlesStreamEventThinkingDelta() {
        String json =
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"considering options\"}}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, ClaudeCodeLogFormat.INSTANCE);
        assertEquals("thinking", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesStreamEventTextDelta() {
        String json =
                "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Here is the result.\"}}}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(1, json, ClaudeCodeLogFormat.INSTANCE);
        assertEquals("assistant", line.toEventView().getCategory());
    }

    @Test
    void parseLine_handlesErrorWithErrorField() {
        String json = "{\"type\":\"error\",\"error\":\"Rate limit exceeded\"}";
        AiAgentLogParser.ParsedLine line = AiAgentLogParser.parseLine(1, json);
        assertEquals("error", line.toEventView().getCategory());
    }

    @Test
    void parseLine_generatesToolCallIdWhenMissing() {
        String json = "{\"type\":\"tool_call\",\"tool_name\":\"bash\",\"text\":\"ls\"}";
        AiAgentLogParser.ParsedLine line =
                AiAgentLogParser.parseLine(42, json, CursorLogFormat.INSTANCE);
        assertEquals("tool-call-42", line.getToolCallIdOrGenerated());
    }

    @Test
    void eventView_categoryLabelsAreReadable() {
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
    void eventView_inlineContentForAssistantAndResult() {
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
    void parse_returnsEmptyForNullFile() throws IOException {
        List<AiAgentLogParser.EventView> events = AiAgentLogParser.parse(null);
        assertTrue(events.isEmpty());
    }

    @Test
    void parse_returnsEmptyForNonexistentFile() throws IOException {
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(new File("/nonexistent/path.jsonl"));
        assertTrue(events.isEmpty());
    }

    // ======================== Full Fixture Event Count Tests
    // ========================

    @Test
    void claudeCodeConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("claude-code-conversation.jsonl", ClaudeCodeLogFormat.INSTANCE);
        assertTrue(events.size() >= 5, "Should have at least 5 events for a full conversation");
    }

    @Test
    void codexConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("codex-conversation.jsonl", CodexLogFormat.INSTANCE);
        assertEquals(6, events.size(), "Current Codex fixture should produce 6 visible events");
    }

    @Test
    void cursorConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("cursor-agent-conversation.jsonl", CursorLogFormat.INSTANCE);
        assertTrue(events.size() >= 6, "Should have at least 6 events");
    }

    @Test
    void geminiConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("gemini-cli-conversation.jsonl", ClaudeCodeLogFormat.INSTANCE);
        assertEquals(6, events.size(), "Current Gemini fixture should produce 6 visible events");
    }

    @Test
    void openCodeConversation_hasCorrectEventCount() throws IOException {
        List<AiAgentLogParser.EventView> events =
                parseFixture("opencode-conversation.jsonl", OpenCodeLogFormat.INSTANCE);
        assertEquals(4, events.size(), "Current OpenCode fixture should produce 4 visible events");
    }

    // ======================== Markdown rendering ========================

    @Test
    void contentHtml_convertsBasicMarkdown() {
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
        assertTrue(html.contains("<strong>world</strong>"), "Should contain <strong>");
    }

    @Test
    void contentHtml_convertsCodeBlocks() {
        String md = "Here:\n```\nfoo()\n```\nDone.";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue(html.contains("<pre><code>"), "Should contain <pre><code>");
        assertTrue(html.contains("foo()"), "Should contain foo()");
    }

    @Test
    void contentHtml_convertsBulletLists() {
        String md = "Items:\n- one\n- two\n- three";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue(html.contains("<ul>"), "Should contain <ul>");
        assertTrue(html.contains("<li>one</li>"), "Should contain <li>one</li>");
    }

    @Test
    void contentHtml_convertsHeaders() {
        String md = "# Title\n## Subtitle";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue(html.contains("<strong>Title</strong>"), "Should contain strong for title");
        assertTrue(
                html.contains("<strong>Subtitle</strong>"), "Should contain strong for subtitle");
    }

    @Test
    void contentHtml_escapesHtmlInContent() {
        String md = "Use <div> tags & \"quotes\"";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue(html.contains("&lt;div&gt;"), "Should escape <");
        assertTrue(html.contains("&amp;"), "Should escape &");
    }

    @Test
    void contentHtml_handlesEmptyString() {
        assertEquals("", AiAgentLogParser.EventView.markdownToHtml(""));
        assertEquals("", AiAgentLogParser.EventView.markdownToHtml(null));
    }

    @Test
    void contentHtml_convertsInlineCode() {
        String md = "Run `echo hello` now";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue(html.contains("<code>echo hello</code>"), "Should contain <code>");
    }

    @Test
    void contentHtml_escapesHtmlInsideInlineCode() {
        String md = "Run `<img src=x onerror=alert(1)>` now";
        String html = AiAgentLogParser.EventView.markdownToHtml(md);
        assertTrue(
                html.contains("<code>&lt;img src=x onerror=alert(1)&gt;</code>"),
                "Inline code should remain escaped");
        assertFalse(html.contains("<img"), "Rendered markdown should not reintroduce raw HTML");
    }

    // ======================== Result deduplication ========================

    @Test
    void parse_deduplicatesResultThatRepeatsAssistant() throws IOException {
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
        assertNotNull(assistant, "Should have assistant event");
        assertEquals("The answer is 42.", assistant.getContent());

        AiAgentLogParser.EventView result =
                events.stream()
                        .filter(e -> "result".equals(e.getCategory()))
                        .findFirst()
                        .orElse(null);
        assertNull(result, "Deduped result should be fully skipped");
    }

    @Test
    void parse_keepsResultWhenDifferentFromAssistant() throws IOException {
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
        assertNotNull(result, "Should have result event");
        assertEquals("Completely different result.", result.getContent());
    }
}
