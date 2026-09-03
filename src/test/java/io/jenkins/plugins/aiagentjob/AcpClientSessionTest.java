package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Proc;

import io.jenkins.plugins.aiagentjob.grokbuild.GrokBuildAgentHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@WithJenkins
class AcpClientSessionTest {

    @TempDir Path tempDirectory;

    @Test
    void usesApiKeyWhenProcessBootstrapReportsIt(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","method":"ai-agent/auth_environment","params":{"name":"XAI_API_KEY"}}
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of("XAI_API_KEY", "xai.api_key"),
                            List.of("cached_token"),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesCachedIdentityWhenProcessBootstrapDoesNotProvideApiKey(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of("XAI_API_KEY", "xai.api_key"),
                            List.of("cached_token"),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"cached_token\""));
            assertFalse(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesAdvertisedApiKeyWhenConfiguredOutsideProcessEnvironment(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"}]}}
                {"jsonrpc":"2.0","id":2,"result":{}}
                {"jsonrpc":"2.0","id":3,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":4,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);
        AiAgentBuilder config = new AiAgentBuilder();
        config.setAgent(new GrokBuildAgentHandler());
        AiAgentTypeHandler.AcpExecutionSpec execution = config.getAgent().buildAcpExecution(config);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            execution.getAuthenticationMethods(),
                            execution.getFallbackAuthenticationMethods(),
                            Map.of()));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void resolvesArbitraryConfigIdsWithoutCategoriesAndUsesUpdatedOptions(JenkinsRule jenkins)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[{"id":"active-model","name":"Model","type":"select","currentValue":"model-1","options":[{"value":"model-1","name":"Model 1"},{"value":"model-2","name":"Model 2"}]},{"id":"initial-depth","name":"Reasoning effort","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"}]}]}}
                {"jsonrpc":"2.0","id":3,"result":{"configOptions":[{"id":"active-model","name":"Model","type":"select","currentValue":"model-2","options":[{"value":"model-1","name":"Model 1"},{"value":"model-2","name":"Model 2"}]},{"id":"thinking-depth","name":"Reasoning effort","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]}]}}
                {"jsonrpc":"2.0","id":4,"result":{"configOptions":[]}}
                {"jsonrpc":"2.0","id":5,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "model-2",
                            "high",
                            Map.of(),
                            List.of(),
                            Map.of()));
            assertTrue(
                    proc.stdinText()
                            .contains("\"configId\":\"active-model\",\"value\":\"model-2\""));
            assertTrue(
                    proc.stdinText()
                            .contains("\"configId\":\"thinking-depth\",\"value\":\"high\""));
            assertFalse(proc.stdinText().contains("\"configId\":\"initial-depth\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void rejectsUnsupportedAdvertisedConfigValue(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[{"id":"active-model","name":"Model","category":"model","type":"select","currentValue":"model-1","options":[{"value":"model-1","name":"Model 1"}]}]}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "missing-model",
                                            "",
                                            Map.of(),
                                            List.of(),
                                            Map.of()));

            assertTrue(error.getMessage().contains("missing-model"));
            assertTrue(error.getMessage().contains("active-model"));
            assertTrue(error.getMessage().contains("model-1"));
        } finally {
            proc.kill();
        }
    }

    @Test
    void rejectsAmbiguousCategorylessConfigValue(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1","configOptions":[{"id":"quality","name":"Quality","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]},{"id":"thinking-depth","name":"Thinking","type":"select","currentValue":"low","options":[{"value":"low","name":"Low"},{"value":"high","name":"High"}]}]}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "high",
                                            Map.of(),
                                            List.of(),
                                            Map.of()));

            assertTrue(error.getMessage().contains("high"));
            assertTrue(error.getMessage().contains("quality"));
            assertTrue(error.getMessage().contains("thinking-depth"));
        } finally {
            proc.kill();
        }
    }

    @Test
    void timesOutWhenAuthenticationDoesNotRespond(JenkinsRule jenkins) throws Exception {
        String initialize =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"}]}}
                """;
        FakeProc proc = new FakeProc(initialize, true);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(200));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of("XAI_API_KEY", "xai.api_key"),
                                            List.of(),
                                            Map.of("XAI_API_KEY", "fixture-key")));

            assertTrue(error.getMessage().contains("ACP authenticate timed out"));
            assertFalse(proc.isAlive());
        } finally {
            proc.kill();
        }
    }

    @Test
    void startsProtocolTimeoutAfterProcessReadyMarker(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","method":"ai-agent/process_ready"}
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}
                """;
        FakeProc proc = new FakeProc(responses, false, Duration.ofMillis(250));

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(50),
                            true);

            assertTrue(
                    session.execute(
                            tempDirectory.toString(),
                            "respond done",
                            "",
                            "",
                            Map.of(),
                            List.of(),
                            Map.of()));
        } finally {
            proc.kill();
        }
    }

    @Test
    void doesNotFallBackToCachedIdentityAfterApiKeyFailure(JenkinsRule jenkins) throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"authMethods":[{"id":"xai.api_key"},{"id":"cached_token"}]}}
                {"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"invalid API key"}}
                """;
        FakeProc proc = new FakeProc(responses, false);

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            new ExecutionRegistry.LiveExecution(),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1));

            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of("XAI_API_KEY", "xai.api_key"),
                                            List.of("cached_token"),
                                            Map.of("XAI_API_KEY", "invalid-fixture-key")));

            assertTrue(error.getMessage().contains("invalid API key"));
            assertTrue(proc.stdinText().contains("\"methodId\":\"xai.api_key\""));
            assertFalse(proc.stdinText().contains("\"methodId\":\"cached_token\""));
        } finally {
            proc.kill();
        }
    }

    @Test
    void usesAllowAlwaysWhenAllowOnceIsNotOffered(JenkinsRule jenkins) throws Exception {
        assertApprovedPermissionOption(
                """
                [{"optionId":"always","kind":"allow_always"},{"optionId":"reject","kind":"reject_once"}]
                """,
                "always");
    }

    @Test
    void prefersAllowOnceWhenBothAllowOptionsAreOffered(JenkinsRule jenkins) throws Exception {
        assertApprovedPermissionOption(
                """
                [{"optionId":"always","kind":"allow_always"},{"optionId":"once","kind":"allow_once"}]
                """,
                "once");
    }

    private void assertApprovedPermissionOption(String options, String expectedOptionId)
            throws Exception {
        String responses =
                """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}
                {"jsonrpc":"2.0","id":2,"result":{"sessionId":"session-1"}}
                {"jsonrpc":"2.0","id":"permission-1","method":"session/request_permission","params":{"sessionId":"session-1","toolCall":{"toolCallId":"call-1","title":"run command","kind":"execute","rawInput":{"command":"true"}},"options":%s}}
                {"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}
                """
                        .formatted(options.trim());
        FakeProc proc = new FakeProc(responses, false);
        ExecutionRegistry.LiveExecution liveExecution = new ExecutionRegistry.LiveExecution();

        try (AiAgentExecutor.AgentOutputHandler output = newOutputHandler()) {
            AcpClientSession session =
                    new AcpClientSession(
                            proc,
                            proc.getStdout(),
                            proc.getStdin(),
                            output,
                            liveExecution,
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(1));
            CompletableFuture<Boolean> execution =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return session.execute(
                                            tempDirectory.toString(),
                                            "respond done",
                                            "",
                                            "",
                                            Map.of(),
                                            List.of(),
                                            Map.of());
                                } catch (IOException | InterruptedException e) {
                                    throw new CompletionException(e);
                                }
                            });

            ExecutionRegistry.PendingApproval pending = waitForPendingApproval(liveExecution);
            assertTrue(liveExecution.approve(pending.getId()));
            assertTrue(execution.get(2, TimeUnit.SECONDS));
            assertTrue(
                    proc.stdinText()
                            .contains(
                                    "\"outcome\":\"selected\",\"optionId\":\""
                                            + expectedOptionId
                                            + "\""));
        } finally {
            proc.kill();
        }
    }

    private static ExecutionRegistry.PendingApproval waitForPendingApproval(
            ExecutionRegistry.LiveExecution liveExecution) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            List<ExecutionRegistry.PendingApproval> approvals = liveExecution.getPendingApprovals();
            if (!approvals.isEmpty()) {
                return approvals.get(0);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("ACP permission request did not reach Jenkins");
    }

    private AiAgentExecutor.AgentOutputHandler newOutputHandler() throws IOException {
        File rawLog = tempDirectory.resolve("raw-" + System.nanoTime() + ".jsonl").toFile();
        return new AiAgentExecutor.AgentOutputHandler(
                new ByteArrayOutputStream(),
                rawLog,
                new ExecutionRegistry.LiveExecution(),
                List.of());
    }

    private static final class FakeProc extends Proc {
        private final PipedInputStream stdout = new PipedInputStream();
        private final PipedOutputStream serverOutput;
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private volatile boolean alive = true;

        FakeProc(String responses, boolean keepOutputOpen) throws IOException {
            this(responses, keepOutputOpen, Duration.ZERO);
        }

        FakeProc(String responses, boolean keepOutputOpen, Duration delay) throws IOException {
            serverOutput = new PipedOutputStream(stdout);
            if (!delay.isZero()) {
                Thread writer =
                        new Thread(
                                () -> {
                                    try {
                                        Thread.sleep(delay.toMillis());
                                        writeResponses(responses, keepOutputOpen);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        closeServerOutput();
                                    } catch (IOException e) {
                                        closeServerOutput();
                                    }
                                },
                                "fake-acp-response-writer");
                writer.setDaemon(true);
                writer.start();
                return;
            }
            writeResponses(responses, keepOutputOpen);
        }

        private void writeResponses(String responses, boolean keepOutputOpen) throws IOException {
            serverOutput.write(responses.getBytes(StandardCharsets.UTF_8));
            serverOutput.flush();
            if (!keepOutputOpen) {
                serverOutput.close();
            }
        }

        private void closeServerOutput() {
            try {
                serverOutput.close();
            } catch (IOException ignored) {
            }
        }

        String stdinText() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void kill() throws IOException {
            alive = false;
            serverOutput.close();
            stdout.close();
        }

        @Override
        public int join() {
            alive = false;
            return 0;
        }

        @Override
        public InputStream getStdout() {
            return stdout;
        }

        @Override
        public InputStream getStderr() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getStdin() {
            return stdin;
        }
    }
}
