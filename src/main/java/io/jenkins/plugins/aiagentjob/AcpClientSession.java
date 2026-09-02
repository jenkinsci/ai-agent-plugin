package io.jenkins.plugins.aiagentjob;

import hudson.Proc;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal synchronous Agent Client Protocol client for one agent prompt. */
final class AcpClientSession {
    private static final String JSONRPC_VERSION = "2.0";
    static final String AUTH_ENVIRONMENT_METHOD = "ai-agent/auth_environment";
    static final String PROCESS_READY_METHOD = "ai-agent/process_ready";

    private final Proc proc;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AiAgentExecutor.AgentOutputHandler outputHandler;
    private final ExecutionRegistry.LiveExecution liveExecution;
    private final Duration approvalTimeout;
    private final Duration protocolRequestTimeout;
    private final boolean waitForProcessReady;
    private final BlockingQueue<ReadResult> incoming = new ArrayBlockingQueue<>(256);
    private final Set<String> processEnvironmentVariables = new HashSet<>();
    private final Thread readerThread;
    private long nextRequestId;

    AcpClientSession(
            Proc proc,
            InputStream stdout,
            OutputStream stdin,
            AiAgentExecutor.AgentOutputHandler outputHandler,
            ExecutionRegistry.LiveExecution liveExecution,
            Duration approvalTimeout,
            Duration protocolRequestTimeout) {
        this(
                proc,
                stdout,
                stdin,
                outputHandler,
                liveExecution,
                approvalTimeout,
                protocolRequestTimeout,
                false);
    }

    AcpClientSession(
            Proc proc,
            InputStream stdout,
            OutputStream stdin,
            AiAgentExecutor.AgentOutputHandler outputHandler,
            ExecutionRegistry.LiveExecution liveExecution,
            Duration approvalTimeout,
            Duration protocolRequestTimeout,
            boolean waitForProcessReady) {
        this.proc = proc;
        this.reader = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
        this.writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8));
        this.outputHandler = outputHandler;
        this.liveExecution = liveExecution;
        this.approvalTimeout = approvalTimeout;
        this.protocolRequestTimeout = protocolRequestTimeout;
        this.waitForProcessReady = waitForProcessReady;
        this.readerThread = startReaderThread();
    }

    boolean execute(
            String cwd,
            String prompt,
            String model,
            String reasoningEffort,
            Map<String, String> authenticationMethods,
            List<String> fallbackAuthenticationMethods,
            Map<String, String> environment)
            throws IOException, InterruptedException {
        try {
            if (waitForProcessReady) {
                awaitProcessReady();
            }
            JSONObject initialization = initialize();
            authenticate(
                    initialization,
                    authenticationMethods,
                    fallbackAuthenticationMethods,
                    environment);
            String sessionId = newSession(cwd);
            setConfigOption(sessionId, "model", model);
            setConfigOption(sessionId, "effort", reasoningEffort);
            prompt(sessionId, prompt);
            return true;
        } catch (ApprovalDeniedException e) {
            return false;
        } finally {
            readerThread.interrupt();
        }
    }

    private JSONObject initialize() throws IOException, InterruptedException {
        JSONObject fileSystem = object("readTextFile", false, "writeTextFile", false);
        JSONObject capabilities = object("fs", fileSystem, "terminal", false);
        JSONObject params = object("protocolVersion", 1, "clientCapabilities", capabilities);
        return request("initialize", params);
    }

    private void authenticate(
            JSONObject initialization,
            Map<String, String> authenticationMethods,
            List<String> fallbackAuthenticationMethods,
            Map<String, String> environment)
            throws IOException, InterruptedException {
        if (authenticationMethods.isEmpty() && fallbackAuthenticationMethods.isEmpty()) {
            return;
        }

        Set<String> advertisedMethods = advertisedAuthenticationMethods(initialization);
        Set<String> candidates = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : authenticationMethods.entrySet()) {
            String value = environment.get(entry.getKey());
            if (((value != null && !value.trim().isEmpty())
                            || processEnvironmentVariables.contains(entry.getKey()))
                    && advertisedMethods.contains(entry.getValue())) {
                candidates.add(entry.getValue());
            }
        }
        for (String method : fallbackAuthenticationMethods) {
            if (advertisedMethods.contains(method)) {
                candidates.add(method);
            }
        }
        if (candidates.isEmpty()) {
            throw new IOException(
                    "ACP agent did not advertise an authentication method usable with the "
                            + "configured environment.");
        }

        String selectedMethod = candidates.iterator().next();
        request(
                "authenticate",
                object("methodId", selectedMethod, "_meta", object("headless", true)));
    }

    private static Set<String> advertisedAuthenticationMethods(JSONObject initialization) {
        Set<String> methods = new HashSet<>();
        JSONArray advertised = initialization.optJSONArray("authMethods");
        if (advertised == null) {
            return methods;
        }
        for (int i = 0; i < advertised.size(); i++) {
            Object item = advertised.get(i);
            if (item instanceof JSONObject) {
                String id = ((JSONObject) item).optString("id", "").trim();
                if (!id.isEmpty()) {
                    methods.add(id);
                }
            } else if (item != null) {
                String id = String.valueOf(item).trim();
                if (!id.isEmpty()) {
                    methods.add(id);
                }
            }
        }
        return methods;
    }

    private String newSession(String cwd) throws IOException, InterruptedException {
        JSONObject params = object("cwd", cwd, "mcpServers", new JSONArray());
        JSONObject result = request("session/new", params);
        String sessionId = result.optString("sessionId", "").trim();
        if (sessionId.isEmpty()) {
            throw new IOException("ACP agent returned no session ID.");
        }
        return sessionId;
    }

    private void setConfigOption(String sessionId, String configId, String value)
            throws IOException, InterruptedException {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        JSONObject params =
                object("sessionId", sessionId, "configId", configId, "value", value.trim());
        request("session/set_config_option", params);
    }

    private void prompt(String sessionId, String prompt) throws IOException, InterruptedException {
        JSONArray content = new JSONArray();
        content.add(object("type", "text", "text", prompt));
        request("session/prompt", object("sessionId", sessionId, "prompt", content));
    }

    private JSONObject request(String method, JSONObject params)
            throws IOException, InterruptedException {
        long requestId = ++nextRequestId;
        boolean boundedRequest = !"session/prompt".equals(method);
        long requestStartedNanos = System.nanoTime();
        send(
                object(
                        "jsonrpc",
                        JSONRPC_VERSION,
                        "id",
                        requestId,
                        "method",
                        method,
                        "params",
                        params));

        while (true) {
            JSONObject message = readMessage(method, requestStartedNanos, boundedRequest);
            String incomingMethod = message.optString("method", "");
            if (captureAuthenticationEnvironment(message)
                    || PROCESS_READY_METHOD.equals(incomingMethod)) {
                continue;
            }
            if ("session/request_permission".equals(incomingMethod) && message.has("id")) {
                handlePermissionRequest(message);
                continue;
            }
            if (!incomingMethod.isEmpty() && message.has("id")) {
                sendMethodNotFound(message.opt("id"), incomingMethod);
                continue;
            }
            if (!sameId(message.opt("id"), requestId)) {
                continue;
            }

            JSONObject error = message.optJSONObject("error");
            if (error != null) {
                outputHandler.recordLine(message.toString());
                String errorMessage = error.optString("message", error.toString());
                throw new IOException("ACP " + method + " failed: " + errorMessage);
            }
            JSONObject result = message.optJSONObject("result");
            if (result == null) {
                throw new IOException("ACP " + method + " returned no result.");
            }
            if ("session/prompt".equals(method)) {
                outputHandler.recordLine(message.toString());
            }
            return result;
        }
    }

    private void awaitProcessReady() throws IOException, InterruptedException {
        while (true) {
            ReadResult readResult = incoming.take();
            if (readResult.failure != null) {
                throw readResult.failure;
            }
            String line = readResult.line;
            if (line == null) {
                throw new IOException("ACP agent closed stdout before setup completed.");
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                outputHandler.recordLine(line);
                continue;
            }
            try {
                JSONObject message = JSONObject.fromObject(trimmed);
                if (captureAuthenticationEnvironment(message)) {
                    continue;
                }
                if (PROCESS_READY_METHOD.equals(message.optString("method", ""))) {
                    return;
                }
            } catch (RuntimeException ignored) {
            }
            outputHandler.recordLine(line);
        }
    }

    private boolean captureAuthenticationEnvironment(JSONObject message) {
        if (!AUTH_ENVIRONMENT_METHOD.equals(message.optString("method", ""))) {
            return false;
        }
        JSONObject marker = message.optJSONObject("params");
        String name = marker == null ? "" : marker.optString("name", "").trim();
        if (!name.isEmpty()) {
            processEnvironmentVariables.add(name);
        }
        return true;
    }

    private JSONObject readMessage(String method, long requestStartedNanos, boolean boundedRequest)
            throws IOException, InterruptedException {
        while (true) {
            ReadResult readResult;
            if (boundedRequest) {
                long elapsedNanos = System.nanoTime() - requestStartedNanos;
                long remainingNanos = protocolRequestTimeout.toNanos() - elapsedNanos;
                if (remainingNanos <= 0) {
                    throw protocolTimeout(method);
                }
                readResult = incoming.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (readResult == null) {
                    throw protocolTimeout(method);
                }
            } else {
                readResult = incoming.take();
            }
            if (readResult.failure != null) {
                throw readResult.failure;
            }
            String line = readResult.line;
            if (line == null) {
                throw new IOException("ACP agent closed stdout before completing request.");
            }
            String trimmed = line.trim();
            if (!trimmed.startsWith("{")) {
                outputHandler.recordLine(line);
                continue;
            }
            try {
                JSONObject message = JSONObject.fromObject(trimmed);
                if (shouldRecord(message)) {
                    outputHandler.recordLine(line);
                }
                return message;
            } catch (RuntimeException e) {
                outputHandler.recordLine(line);
                throw new IOException("ACP agent returned invalid JSON: " + trimmed, e);
            }
        }
    }

    private IOException protocolTimeout(String method) throws InterruptedException {
        IOException timeout =
                new IOException(
                        "ACP "
                                + method
                                + " timed out after "
                                + protocolRequestTimeout.toSeconds()
                                + "s.");
        try {
            proc.kill();
        } catch (IOException e) {
            timeout.addSuppressed(e);
        }
        return timeout;
    }

    private Thread startReaderThread() {
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    incoming.put(ReadResult.line(line));
                                }
                                incoming.put(ReadResult.endOfStream());
                            } catch (IOException e) {
                                try {
                                    incoming.put(ReadResult.failure(e));
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "ai-agent-acp-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static boolean shouldRecord(JSONObject message) {
        String method = message.optString("method", "");
        if ("session/request_permission".equals(method)) {
            return true;
        }
        if (!"session/update".equals(method)) {
            return false;
        }
        JSONObject params = message.optJSONObject("params");
        JSONObject update = params == null ? null : params.optJSONObject("update");
        if (update == null) {
            return false;
        }
        String type = update.optString("sessionUpdate", "");
        return "agent_message_chunk".equals(type)
                || "user_message_chunk".equals(type)
                || "agent_thought_chunk".equals(type)
                || "tool_call".equals(type)
                || "tool_call_update".equals(type)
                || "usage_update".equals(type);
    }

    private void handlePermissionRequest(JSONObject request)
            throws IOException, InterruptedException {
        JSONObject params = request.optJSONObject("params");
        JSONObject toolCall = params == null ? null : params.optJSONObject("toolCall");
        if (params == null || toolCall == null) {
            sendMethodNotFound(request.opt("id"), "session/request_permission");
            return;
        }

        String toolCallId =
                LogFormatUtils.firstNonEmpty(toolCall, "toolCallId", "tool_call_id", "id");
        if (toolCallId.isEmpty()) {
            toolCallId = String.valueOf(request.opt("id"));
        }
        String toolName = LogFormatUtils.firstNonEmpty(toolCall, "kind", "title");
        if (toolName.isEmpty()) {
            toolName = "tool";
        }
        JSONObject rawInput = toolCall.optJSONObject("rawInput");
        String summary = LogFormatUtils.extractToolInput(rawInput, toolName);
        if (summary.isEmpty()) {
            summary = LogFormatUtils.firstNonEmpty(toolCall, "title");
        }

        toolCallId = outputHandler.maskSensitiveValues(toolCallId);
        toolName = outputHandler.maskSensitiveValues(toolName);
        summary = outputHandler.maskSensitiveValues(summary);

        ExecutionRegistry.PendingApproval pending =
                liveExecution.createPendingApproval(toolCallId, toolName, summary);
        outputHandler.writeStatus(
                "Approval required: "
                        + pending.getToolName()
                        + " ("
                        + pending.getToolCallId()
                        + ")");

        ExecutionRegistry.ApprovalDecision decision =
                liveExecution.awaitDecision(pending, approvalTimeout);
        if (!proc.isAlive()) {
            outputHandler.writeStatus("Approval denied: " + decision.getReason());
            throw new ApprovalDeniedException();
        }
        JSONArray options = params.optJSONArray("options");
        String optionId =
                findPermissionOption(
                        options,
                        decision.isApproved()
                                ? new String[] {"allow_once", "allow_always"}
                                : new String[] {"reject_once", "reject_always"});
        boolean approved = decision.isApproved() && optionId != null;

        JSONObject outcome;
        if (optionId == null) {
            outcome = object("outcome", "cancelled");
        } else {
            outcome = object("outcome", "selected", "optionId", optionId);
        }
        send(
                object(
                        "jsonrpc",
                        JSONRPC_VERSION,
                        "id",
                        request.opt("id"),
                        "result",
                        object("outcome", outcome)));

        if (!approved) {
            String reason =
                    decision.isApproved()
                            ? "agent did not offer an allow option"
                            : decision.getReason();
            outputHandler.writeStatus("Approval denied: " + reason);
            throw new ApprovalDeniedException();
        }
        outputHandler.writeStatus("Approval granted: " + pending.getToolName());
    }

    private static String findPermissionOption(JSONArray options, String[] acceptedKinds) {
        if (options == null) {
            return null;
        }
        for (String acceptedKind : acceptedKinds) {
            for (int i = 0; i < options.size(); i++) {
                Object item = options.get(i);
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                JSONObject option = (JSONObject) item;
                if (acceptedKind.equals(option.optString("kind", ""))) {
                    String optionId = option.optString("optionId", "").trim();
                    if (!optionId.isEmpty()) {
                        return optionId;
                    }
                }
            }
        }
        return null;
    }

    private void sendMethodNotFound(Object id, String method) throws IOException {
        JSONObject error =
                object("code", -32601, "message", "Unsupported ACP client method: " + method);
        send(object("jsonrpc", JSONRPC_VERSION, "id", id, "error", error));
    }

    private synchronized void send(JSONObject message) throws IOException {
        writer.write(message.toString());
        writer.newLine();
        writer.flush();
    }

    private static boolean sameId(Object value, long expected) {
        return value != null && String.valueOf(expected).equals(String.valueOf(value));
    }

    private static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private static final class ApprovalDeniedException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class ReadResult {
        private final String line;
        private final IOException failure;

        private ReadResult(String line, IOException failure) {
            this.line = line;
            this.failure = failure;
        }

        static ReadResult line(String line) {
            return new ReadResult(line, null);
        }

        static ReadResult endOfStream() {
            return new ReadResult(null, null);
        }

        static ReadResult failure(IOException failure) {
            return new ReadResult(null, failure);
        }
    }
}
