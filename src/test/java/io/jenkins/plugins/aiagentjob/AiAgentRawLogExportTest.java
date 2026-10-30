package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;

import org.junit.jupiter.api.Test;

class AiAgentRawLogExportTest {

    @Test
    void emptyPathDisablesExport() throws Exception {
        assertNull(AiAgentRawLogExport.validationError(null));
        assertNull(AiAgentRawLogExport.validationError(""));
        assertNull(AiAgentRawLogExport.validationError("   "));
        assertNull(AiAgentRawLogExport.resolve("  "));
    }

    @Test
    void acceptsWorkspaceRelativeFiles() throws Exception {
        assertNull(AiAgentRawLogExport.validationError("agent.jsonl"));
        assertNull(AiAgentRawLogExport.validationError("ai-logs/review.jsonl"));
        assertNull(AiAgentRawLogExport.validationError("ai-logs\\review.jsonl"));
        assertNull(AiAgentRawLogExport.validationError("./ai-logs/review.jsonl"));
        assertNull(AiAgentRawLogExport.validationError("ai-logs/..hidden/review.jsonl"));
        assertEquals("ai-logs/review.jsonl", AiAgentRawLogExport.resolve(" ai-logs/review.jsonl "));
    }

    @Test
    void resolveNormalizesSeparatorsAndDotSegments() throws Exception {
        assertEquals("ai-logs/review.jsonl", AiAgentRawLogExport.resolve("ai-logs\\review.jsonl"));
        assertEquals("run.jsonl", AiAgentRawLogExport.resolve("./run.jsonl"));
        assertEquals("run.jsonl", AiAgentRawLogExport.resolve(".\\run.jsonl"));
        assertEquals(
                "ai-logs/sub/review.jsonl",
                AiAgentRawLogExport.resolve("./ai-logs//./sub\\review.jsonl"));
    }

    @Test
    void rejectsAbsolutePaths() {
        assertError("/var/log/agent.jsonl", "relative to the workspace");
        assertError("\\\\server\\share\\agent.jsonl", "relative to the workspace");
        assertError("C:\\logs\\agent.jsonl", "relative to the workspace");
        assertError("c:agent.jsonl", "relative to the workspace");
    }

    @Test
    void rejectsDriveRelativePathsHiddenBehindDotSegments() {
        assertError("./D:outside.jsonl", "relative to the workspace");
        assertError(".\\D:outside.jsonl", "relative to the workspace");
        assertError(".//./D:\\logs\\outside.jsonl", "relative to the workspace");
        assertThrows(AbortException.class, () -> AiAgentRawLogExport.resolve("./D:outside.jsonl"));
        assertThrows(AbortException.class, () -> AiAgentRawLogExport.resolve(".\\D:outside.jsonl"));
    }

    @Test
    void rejectsParentTraversal() {
        assertError("../agent.jsonl", "cannot contain '..'");
        assertError("ai-logs/../../agent.jsonl", "cannot contain '..'");
        assertError("ai-logs\\..\\..\\agent.jsonl", "cannot contain '..'");
        assertError("..", "cannot contain '..'");
    }

    @Test
    void rejectsDirectoryTargets() {
        assertError("ai-logs/", "must name a file");
        assertError("ai-logs\\", "must name a file");
        assertError(".", "must name a file");
        assertError("ai-logs/.", "must name a file");
    }

    @Test
    void resolveFailsWithAbortException() {
        AbortException failure =
                assertThrows(
                        AbortException.class, () -> AiAgentRawLogExport.resolve("../escape.jsonl"));
        assertTrue(failure.getMessage().startsWith("[ai-agent] "), failure.getMessage());
    }

    private static void assertError(String path, String expectedFragment) {
        String error = AiAgentRawLogExport.validationError(path);
        assertNotNull(error, "Expected a validation error for " + path);
        assertTrue(error.contains(expectedFragment), error);
    }
}
