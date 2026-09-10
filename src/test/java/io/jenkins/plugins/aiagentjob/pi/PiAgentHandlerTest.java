package io.jenkins.plugins.aiagentjob.pi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.AiAgentBuilder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class PiAgentHandlerTest {
    @TempDir Path temp;

    private final PiAgentHandler handler = new PiAgentHandler();

    private AiAgentBuilder config() {
        AiAgentBuilder config = new AiAgentBuilder();
        config.setAgent(handler);
        return config;
    }

    @Test
    void defaultsToEphemeralJsonWithReadOnlyTools() {
        List<String> command = handler.buildDefaultCommand(config(), "Review the code");
        assertEquals(
                List.of(
                        "pi",
                        "--print",
                        "--mode",
                        "json",
                        "--no-session",
                        "--no-extensions",
                        "--no-approve",
                        "--tools",
                        "read,grep,find,ls",
                        "Review the code"),
                command);
    }

    @Test
    void yoloTrustsProjectAndEnablesDefaultTools() {
        AiAgentBuilder config = config();
        config.setYoloMode(true);
        List<String> command = handler.buildDefaultCommand(config, "Fix tests");
        assertTrue(command.contains("--approve"));
        assertFalse(command.contains("--no-approve"));
        assertFalse(command.contains("--tools"));
    }

    @Test
    void modelShorthandAndExplicitThinkingPreserveProvider() {
        AiAgentBuilder config = config();
        config.setModel("openai/gpt-5.5:xhigh");
        List<String> command = handler.buildDefaultCommand(config, "Review");
        assertEquals("openai/gpt-5.5", command.get(command.indexOf("--model") + 1));
        assertEquals("xhigh", command.get(command.indexOf("--thinking") + 1));
        config.setReasoningEffort("off");
        command = handler.buildDefaultCommand(config, "Review");
        assertEquals("off", command.get(command.indexOf("--thinking") + 1));
        config.setReasoningEffort("invalid");
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.buildDefaultCommand(config, "Review"));
    }

    @Test
    void promptsCannotBecomeOptionsOrFileAttachments() {
        for (String prompt : List.of("--help", "@private.txt", "- Review this")) {
            List<String> command = handler.buildDefaultCommand(config(), prompt);
            assertEquals("\n" + prompt, command.get(command.size() - 1));
        }
    }

    @Test
    void credentialsRequireAnExplicitProviderEnvironmentVariable() {
        AiAgentBuilder config = config();
        config.setApiCredentialsId("provider-key");
        assertThrows(IllegalArgumentException.class, () -> handler.validateExecution(config));
        config.setApiEnvVarName("OPENAI_API_KEY");
        handler.validateExecution(config);
    }

    @Test
    void rejectsManualApprovals() {
        AiAgentBuilder config = config();
        config.setRequireApprovals(true);
        assertThrows(IllegalArgumentException.class, () -> handler.validateExecution(config));
    }

    @Test
    void detectsFinalJsonErrorsButAllowsRecoveredRetries() throws Exception {
        Path log = temp.resolve("pi.jsonl");
        String error =
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"error\"}}\n";
        String success =
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"stopReason\":\"stop\"}}\n";
        Files.writeString(log, "startup log\n" + error);
        assertEquals(1, handler.resolveExitCode(0, log.toFile()));
        assertEquals(42, handler.resolveExitCode(42, log.toFile()));
        Files.writeString(log, error + success);
        assertEquals(0, handler.resolveExitCode(0, log.toFile()));
        Files.writeString(log, success + error.replace("error", "aborted"));
        assertEquals(1, handler.resolveExitCode(0, log.toFile()));
        assertEquals(0, handler.resolveExitCode(0, null));
    }
}
