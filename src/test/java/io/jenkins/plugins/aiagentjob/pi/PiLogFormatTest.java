package io.jenkins.plugins.aiagentjob.pi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class PiLogFormatTest {
    @TempDir Path temp;

    private Path fixture() throws Exception {
        Path file = temp.resolve("conversation.jsonl");
        try (InputStream input =
                getClass()
                        .getResourceAsStream(
                                "/io/jenkins/plugins/aiagentjob/fixtures/pi-conversation.jsonl")) {
            Files.copy(input, file);
        }
        return file;
    }

    @Test
    void rendersCompletedBlocksAndToolsWithoutRepeatedSnapshots() throws Exception {
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(fixture().toFile(), PiLogFormat.INSTANCE);
        assertEquals(
                List.of("user", "thinking", "assistant", "tool_call", "tool_result", "assistant"),
                events.stream().map(AiAgentLogParser.EventView::getCategory).toList());
        assertEquals("Inspect the build.", events.get(1).getContent());
        assertEquals("I will run the check.", events.get(2).getContent());
        assertTrue(events.get(3).getToolInput().contains("printf"));
        assertTrue(events.get(3).getToolInput().contains("timeout"));
        assertEquals("safe-ok\n", events.get(4).getToolOutput());
        assertEquals("bash", events.get(4).getLabel());
        assertEquals("The check passed.", events.get(5).getContent());
    }

    @Test
    void countsUsageOnceAcrossMessageAndAgentSnapshots() throws Exception {
        AgentUsageStats stats =
                AgentUsageStats.fromLogFile(fixture().toFile(), PiStatsExtractor.INSTANCE);
        assertEquals(150, stats.getInputTokens());
        assertEquals(30, stats.getOutputTokens());
        assertEquals(30, stats.getCacheReadTokens());
        assertEquals(10, stats.getCacheWriteTokens());
        assertEquals(8, stats.getReasoningTokens());
        assertEquals(220, stats.getTotalTokens());
        assertEquals(0.012, stats.getCostUsd(), 0.000001);
        assertEquals(2, stats.getNumTurns());
        assertEquals(1, stats.getToolCalls());
        assertEquals("fixture-model", stats.getDetectedModel());
    }

    @Test
    void errorsImagesAndMultilineResultsRemainInspectable() throws Exception {
        Path file = temp.resolve("errors.jsonl");
        Files.writeString(
                file,
                """
                {"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"Inspect"},{"type":"image","mimeType":"image/png","data":"synthetic-image"}]}}
                {"type":"tool_execution_end","toolCallId":"bad","toolName":"bash","result":{"content":[{"type":"text","text":"line one"},{"type":"text","text":"line two"}]},"isError":true}
                {"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"fixture provider unavailable"}}
                """);
        List<AiAgentLogParser.EventView> events =
                AiAgentLogParser.parse(file.toFile(), PiLogFormat.INSTANCE);
        assertEquals("Inspect\n[Image: image/png]", events.get(0).getContent());
        assertFalse(events.get(0).getContent().contains("synthetic-image"));
        assertEquals("Error: line one\nline two", events.get(1).getToolOutput());
        assertEquals("error", events.get(2).getCategory());
        assertEquals("fixture provider unavailable", events.get(2).getContent());
    }
}
