package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeStatsExtractor;
import io.jenkins.plugins.aiagentjob.codex.CodexStatsExtractor;
import io.jenkins.plugins.aiagentjob.cursor.CursorStatsExtractor;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeStatsExtractor;

import net.sf.json.JSONObject;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

class AgentUsageStatsTest {

    private File fixtureFile(String name) throws IOException {
        File tempFile = File.createTempFile("stats-", ".jsonl");
        tempFile.deleteOnExit();
        try (InputStream is = getClass().getResourceAsStream("fixtures/" + name)) {
            if (is == null) throw new IllegalStateException("Fixture not found: " + name);
            Files.copy(is, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    private AgentUsageStats parseStats(String name, AiAgentStatsExtractor extractor)
            throws IOException {
        return AgentUsageStats.fromLogFile(fixtureFile(name), extractor);
    }

    // ======================== Claude Code ========================

    @Test
    void claudeCode_extractsCostAndTokens() throws IOException {
        AgentUsageStats stats =
                parseStats("stats-claude-code.jsonl", ClaudeCodeStatsExtractor.INSTANCE);

        assertTrue(stats.hasData(), "Should have data");
        assertEquals(12500, stats.getInputTokens());
        assertEquals(30, stats.getOutputTokens());
        assertEquals(11000, stats.getCacheReadTokens());
        assertEquals(1000, stats.getCacheWriteTokens());
        assertEquals("$0.30", stats.getCostDisplay());
        assertEquals(2745, stats.getDurationMs());
        assertEquals(2627, stats.getApiDurationMs());
        assertEquals(1, stats.getNumTurns());
    }

    @Test
    void claudeCode_durationFormattedCorrectly() throws IOException {
        AgentUsageStats stats =
                parseStats("stats-claude-code.jsonl", ClaudeCodeStatsExtractor.INSTANCE);
        assertEquals("2.7s", stats.getDurationDisplay());
    }

    @Test
    void claudeCode_totalTokensComputedFromComponents() throws IOException {
        AgentUsageStats stats =
                parseStats("stats-claude-code.jsonl", ClaudeCodeStatsExtractor.INSTANCE);
        long expected = 12500 + 30 + 11000 + 1000;
        assertEquals(expected, stats.getTotalTokens());
    }

    // ======================== Gemini ========================

    @Test
    void gemini_extractsStatsBlock() throws IOException {
        AgentUsageStats stats = parseStats("stats-gemini.jsonl", ClaudeCodeStatsExtractor.INSTANCE);

        assertTrue(stats.hasData());
        assertEquals(4925, stats.getTotalTokens());
        assertEquals(4824, stats.getInputTokens());
        assertEquals(2, stats.getOutputTokens());
        assertEquals(500, stats.getCacheReadTokens());
        assertEquals(3289, stats.getDurationMs());
        assertEquals(0, stats.getToolCalls());
    }

    @Test
    void gemini_noCostReturnsEmptyDisplay() throws IOException {
        AgentUsageStats stats = parseStats("stats-gemini.jsonl", ClaudeCodeStatsExtractor.INSTANCE);
        assertEquals("", stats.getCostDisplay());
    }

    // ======================== Codex ========================

    @Test
    void codex_extractsTurnCompletedUsage() throws IOException {
        AgentUsageStats stats = parseStats("stats-codex.jsonl", CodexStatsExtractor.INSTANCE);

        assertTrue(stats.hasData());
        assertEquals(25602, stats.getInputTokens());
        assertEquals(116, stats.getOutputTokens());
        assertEquals(3456, stats.getCacheReadTokens());
    }

    @Test
    void codex_noCostOrDuration() throws IOException {
        AgentUsageStats stats = parseStats("stats-codex.jsonl", CodexStatsExtractor.INSTANCE);
        assertEquals("", stats.getCostDisplay());
        assertEquals("", stats.getDurationDisplay());
    }

    // ======================== OpenCode ========================

    @Test
    void openCode_extractsStepFinishTokensAndCost() throws IOException {
        AgentUsageStats stats = parseStats("stats-opencode.jsonl", OpenCodeStatsExtractor.INSTANCE);

        assertTrue(stats.hasData());
        assertEquals(198, stats.getInputTokens());
        assertEquals(55, stats.getOutputTokens());
        assertEquals(12, stats.getReasoningTokens());
        assertEquals(150, stats.getCacheReadTokens());
        assertEquals(25767, stats.getCacheWriteTokens());
        assertEquals(26020, stats.getTotalTokens());
        assertEquals("$0.01", stats.getCostDisplay());
    }

    @Test
    void openCode_multiStepAggregates() throws IOException {
        AgentUsageStats stats =
                parseStats("stats-opencode-multi-step.jsonl", OpenCodeStatsExtractor.INSTANCE);

        assertTrue(stats.hasData());
        assertEquals(100 + 200, stats.getInputTokens());
        assertEquals(50 + 30, stats.getOutputTokens());
        assertEquals(10 + 5, stats.getReasoningTokens());
        assertEquals(80 + 100, stats.getCacheReadTokens());
        assertEquals(9000 + 7000, stats.getCacheWriteTokens());
        assertEquals(10000 + 8000, stats.getTotalTokens());
        assertEquals(0.005 + 0.003, stats.getCostUsd(), 0.0001);
    }

    // ======================== Cursor ========================

    @Test
    void cursor_extractsResultUsage() throws IOException {
        AgentUsageStats stats = parseStats("stats-cursor.jsonl", CursorStatsExtractor.INSTANCE);

        assertTrue(stats.hasData());
        assertEquals(103854, stats.getInputTokens());
        assertEquals(1583, stats.getOutputTokens());
        assertEquals(90357, stats.getCacheReadTokens());
        assertEquals(13489, stats.getCacheWriteTokens());
        assertEquals(62643, stats.getDurationMs());
    }

    @Test
    void cursor_durationFormatsMinutes() throws IOException {
        AgentUsageStats stats = parseStats("stats-cursor.jsonl", CursorStatsExtractor.INSTANCE);
        assertEquals("1m 2s", stats.getDurationDisplay());
    }

    // ======================== Edge cases ========================

    @Test
    void nullFile_returnsEmptyStats() throws IOException {
        AgentUsageStats stats = AgentUsageStats.fromLogFile(null);
        assertFalse(stats.hasData());
        assertEquals("", stats.getCostDisplay());
        assertEquals("", stats.getDurationDisplay());
    }

    @Test
    void nonExistentFile_returnsEmptyStats() throws IOException {
        AgentUsageStats stats = AgentUsageStats.fromLogFile(new File("/nonexistent/path.jsonl"));
        assertFalse(stats.hasData());
    }

    @Test
    void emptyFile_returnsEmptyStats() throws IOException {
        File empty = File.createTempFile("empty-", ".jsonl");
        empty.deleteOnExit();
        AgentUsageStats stats = AgentUsageStats.fromLogFile(empty);
        assertFalse(stats.hasData());
    }

    @Test
    void malformedJson_isSkippedGracefully() throws IOException {
        File temp = File.createTempFile("bad-", ".jsonl");
        temp.deleteOnExit();
        Files.write(
                temp.toPath(),
                java.util.Arrays.asList(
                        "not json at all",
                        "{broken",
                        "{\"type\":\"result\",\"total_cost_usd\":0.15,\"duration_ms\":1000,"
                                + "\"usage\":{\"input_tokens\":500,\"output_tokens\":50}}"));
        AgentUsageStats stats = AgentUsageStats.fromLogFile(temp);
        assertTrue(stats.hasData());
        assertEquals(500, stats.getInputTokens());
        assertEquals(50, stats.getOutputTokens());
        assertEquals("$0.15", stats.getCostDisplay());
    }

    @Test
    void extractFrom_directJsonObject() {
        AgentUsageStats stats = new AgentUsageStats();
        JSONObject json =
                JSONObject.fromObject(
                        "{\"type\":\"result\",\"total_cost_usd\":1.5,"
                                + "\"duration_ms\":120500,"
                                + "\"usage\":{\"input_tokens\":50000,\"output_tokens\":2000}}");
        stats.extractFrom(json);

        assertEquals(50000, stats.getInputTokens());
        assertEquals(2000, stats.getOutputTokens());
        assertEquals(1.5, stats.getCostUsd(), 0.001);
        assertEquals("$1.50", stats.getCostDisplay());
        assertEquals("2m 0s", stats.getDurationDisplay());
    }

    @Test
    void durationDisplay_milliseconds() {
        AgentUsageStats stats = new AgentUsageStats();
        JSONObject json =
                JSONObject.fromObject(
                        "{\"type\":\"result\",\"duration_ms\":500,"
                                + "\"usage\":{\"input_tokens\":1}}");
        stats.extractFrom(json);
        assertEquals("500ms", stats.getDurationDisplay());
    }

    @Test
    void totalTokens_usesExplicitFieldWhenPresent() {
        AgentUsageStats stats = new AgentUsageStats();
        JSONObject json =
                JSONObject.fromObject(
                        "{\"type\":\"result\",\"stats\":{\"total_tokens\":9999,"
                                + "\"input_tokens\":5000,\"output_tokens\":100,"
                                + "\"duration_ms\":1000}}");
        stats.extractFrom(json);
        assertEquals(9999, stats.getTotalTokens());
    }

    // ======================== Formatted display ========================

    @Test
    void formattedTokens_haveCommaSeparators() throws IOException {
        AgentUsageStats stats = parseStats("stats-cursor.jsonl", CursorStatsExtractor.INSTANCE);
        assertEquals("103,854", stats.getInputTokensDisplay());
        assertEquals("1,583", stats.getOutputTokensDisplay());
        assertEquals("90,357", stats.getCacheReadTokensDisplay());
        assertEquals("13,489", stats.getCacheWriteTokensDisplay());
    }

    @Test
    void formattedTokens_smallNumbersNoComma() {
        AgentUsageStats stats = new AgentUsageStats();
        JSONObject json =
                JSONObject.fromObject(
                        "{\"type\":\"result\",\"usage\":{\"input_tokens\":42,\"output_tokens\":5}}");
        stats.extractFrom(json);
        assertEquals("42", stats.getInputTokensDisplay());
        assertEquals("5", stats.getOutputTokensDisplay());
    }

    // ======================== Model detection ========================

    @Test
    void detectsModel_fromSystemInit() throws IOException {
        AgentUsageStats stats =
                parseStats("stats-claude-code.jsonl", ClaudeCodeStatsExtractor.INSTANCE);
        assertEquals("claude-test-4", stats.getDetectedModel());
    }

    @Test
    void detectsModel_fromGeminiInit() throws IOException {
        AgentUsageStats stats = parseStats("stats-gemini.jsonl", ClaudeCodeStatsExtractor.INSTANCE);
        assertEquals("gemini-test-pro", stats.getDetectedModel());
    }

    @Test
    void detectsModel_fromCursorInit() throws IOException {
        AgentUsageStats stats = parseStats("stats-cursor.jsonl", CursorStatsExtractor.INSTANCE);
        assertEquals("test-model-4", stats.getDetectedModel());
    }

    @Test
    void detectsModel_emptyWhenNoInit() throws IOException {
        AgentUsageStats stats = parseStats("stats-codex.jsonl", CodexStatsExtractor.INSTANCE);
        assertEquals("", stats.getDetectedModel());
    }
}
