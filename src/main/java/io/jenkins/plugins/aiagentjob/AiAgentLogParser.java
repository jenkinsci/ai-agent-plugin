package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Parses JSONL output from AI agents into classified {@link EventView} objects. Handles Claude
 * Code, Codex, Cursor Agent, OpenCode, and Gemini CLI stream formats.
 */
final class AiAgentLogParser {

    private AiAgentLogParser() {}

    static List<EventView> parse(File rawLogFile) throws IOException {
        if (rawLogFile == null || !rawLogFile.exists()) {
            return Collections.emptyList();
        }
        List<EventView> events = new ArrayList<>();
        String lastAssistantContent = "";
        try (BufferedReader reader =
                Files.newBufferedReader(rawLogFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            long idx = 0;
            while ((line = reader.readLine()) != null) {
                idx++;
                EventView ev = parseLine(idx, line).toEventView();
                if (ev.isEmpty()) continue;

                if (mergeAssistantDelta(events, ev)) {
                    EventView merged = events.get(events.size() - 1);
                    lastAssistantContent = merged.getContent();
                    continue;
                }

                if ("assistant".equals(ev.getCategory()) && !ev.getContent().isEmpty()) {
                    lastAssistantContent = ev.getContent();
                }

                if ("result".equals(ev.getCategory())
                        && !ev.getContent().isEmpty()
                        && !lastAssistantContent.isEmpty()
                        && ev.getContent().contains(lastAssistantContent)) {
                    ev =
                            new EventView(
                                    ev.getId(),
                                    ev.getCategory(),
                                    ev.getLabel(),
                                    "",
                                    "",
                                    "",
                                    ev.getRawDetails(),
                                    ev.getTimestamp(),
                                    false);
                }

                if (!ev.isEmpty()) {
                    events.add(ev);
                }
            }
        }
        return events;
    }

    private static boolean mergeAssistantDelta(List<EventView> events, EventView ev) {
        if (!ev.isDelta() || !"assistant".equals(ev.getCategory()) || ev.getContent().isEmpty()) {
            return false;
        }
        if (events.isEmpty()) {
            return false;
        }
        EventView previous = events.get(events.size() - 1);
        if (!"assistant".equals(previous.getCategory())) {
            return false;
        }

        String mergedContent = previous.getContent() + ev.getContent();
        String mergedRaw = previous.getRawDetails() + "\n" + ev.getRawDetails();
        EventView merged =
                new EventView(
                        previous.getId(),
                        previous.getCategory(),
                        previous.getLabel(),
                        mergedContent,
                        previous.getToolInput(),
                        previous.getToolOutput(),
                        mergedRaw,
                        ev.getTimestamp(),
                        false);
        events.set(events.size() - 1, merged);
        return true;
    }

    static ParsedLine parseLine(long lineNumber, String line) {
        if (line == null) {
            return ParsedLine.raw(lineNumber, "");
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return ParsedLine.raw(lineNumber, "");
        }

        JSONObject json = tryParseJson(trimmed);
        if (json == null) {
            return ParsedLine.raw(lineNumber, trimmed);
        }
        return classifyJson(lineNumber, json);
    }

    private static ParsedLine classifyJson(long lineNumber, JSONObject json) {
        String type = firstNonEmpty(json, "type", "event", "kind", "subtype");
        String role = firstNonEmpty(json, "role");
        String typeLower = normalize(type);
        String roleLower = normalize(role);
        String rawDetails = json.toString(2);

        // --- Claude Code stream-json ---

        if (typeLower.equals("system")) {
            String subtype = normalize(firstNonEmpty(json, "subtype"));
            String modelField = firstNonEmpty(json, "model");
            String label = "System" + (!subtype.isEmpty() ? " " + subtype : "");
            String content = !modelField.isEmpty() ? "Model: " + modelField : extractText(json);
            return ParsedLine.system(lineNumber, label, content, rawDetails);
        }

        if (typeLower.equals("result")) {
            String resultText = firstNonEmpty(json, "result", "error");
            boolean isError = json.optBoolean("is_error", false);
            if (resultText.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            String durationMs = firstNonEmpty(json, "duration_ms");
            String label = isError ? "Error" : "Result";
            String suffix = "";
            if (!durationMs.isEmpty()) {
                try {
                    long ms = Long.parseLong(durationMs);
                    suffix = String.format(" (%.1fs)", ms / 1000.0);
                } catch (NumberFormatException ignored) {
                }
            }
            return ParsedLine.result(
                    lineNumber,
                    isError ? "error" : "result",
                    label + suffix,
                    resultText,
                    rawDetails);
        }

        if (typeLower.equals("init")) {
            String modelField = firstNonEmpty(json, "model");
            if (!modelField.isEmpty()) {
                return ParsedLine.system(lineNumber, "System", "Model: " + modelField, rawDetails);
            }
            String initText = extractText(json);
            if (initText.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.system(lineNumber, "System", initText, rawDetails);
        }

        if (typeLower.equals("step_start")
                || typeLower.equals("step_finish")
                || typeLower.equals("tool_use")
                || typeLower.equals("text")) {
            JSONObject part = json.optJSONObject("part");
            if (part != null) {
                return classifyOpenCodePartEvent(lineNumber, typeLower, part, rawDetails);
            }
        }

        // Claude: assistant/user message with content array
        if (typeLower.equals("assistant") || typeLower.equals("user")) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONArray contentArr = message.optJSONArray("content");
                if (contentArr != null && contentArr.size() > 0) {
                    return classifyClaudeContentArray(
                            lineNumber, typeLower, contentArr, rawDetails);
                }
                String msgText = extractText(message);
                String cat = typeLower.equals("assistant") ? "assistant" : "user";
                return ParsedLine.message(lineNumber, cat, capitalize(cat), msgText, rawDetails);
            }
        }

        // Claude: stream_event
        if (typeLower.equals("stream_event")) {
            JSONObject event = json.optJSONObject("event");
            if (event != null) {
                return classifyClaudeStreamEvent(lineNumber, event, rawDetails);
            }
            return ParsedLine.system(lineNumber, "Stream event", extractText(json), rawDetails);
        }

        // Claude: standalone tool_use
        if (typeLower.equals("tool_use")) {
            String toolName = firstNonEmpty(json, "tool_name", "name");
            String toolCallId = firstNonEmpty(json, "tool_id", "id", "tool_call_id");
            JSONObject toolParameters = json.optJSONObject("input");
            if (toolParameters == null) {
                toolParameters = json.optJSONObject("parameters");
            }
            String toolInput = extractToolInput(toolParameters, toolName);
            if (toolInput.isEmpty()) {
                toolInput = extractText(json);
            }
            if (toolInput.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.toolCall(lineNumber, toolName, toolInput, rawDetails, toolCallId);
        }

        // Claude: standalone tool_result
        if (typeLower.equals("tool_result")) {
            String toolCallId = firstNonEmpty(json, "tool_id", "tool_call_id", "id");
            String toolName = firstNonEmpty(json, "tool_name", "name");
            String output = extractToolResultContent(json);
            if (output.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.toolResult(lineNumber, toolName, output, rawDetails, toolCallId);
        }

        // --- Codex JSONL ---
        JSONObject item = json.optJSONObject("item");
        if (item != null) {
            return classifyCodexItem(lineNumber, typeLower, item, rawDetails);
        }

        if (typeLower.startsWith("thread.") || typeLower.startsWith("turn.")) {
            String text = extractText(json);
            if (text.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.system(lineNumber, "System", text, rawDetails);
        }

        // --- Cursor Agent ---
        if (typeLower.equals("thinking")) {
            String thinkText = firstNonEmpty(json, "text");
            return ParsedLine.thinking(lineNumber, thinkText, rawDetails);
        }

        if (typeLower.equals("tool_call")) {
            return classifyCursorToolCall(lineNumber, json, rawDetails);
        }

        // --- Generic fallback ---
        return classifyFallback(lineNumber, typeLower, roleLower, json, rawDetails);
    }

    private static ParsedLine classifyClaudeContentArray(
            long lineNumber, String parentType, JSONArray contentArr, String rawDetails) {
        // Scan for tool_use first
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            String ciType = normalize(ci.optString("type"));

            if (ciType.equals("tool_use")) {
                String toolName = firstNonEmpty(ci, "name");
                String toolCallId = firstNonEmpty(ci, "id");
                String toolInput = extractToolInput(ci.optJSONObject("input"), toolName);
                return ParsedLine.toolCall(lineNumber, toolName, toolInput, rawDetails, toolCallId);
            }
        }
        // Then tool_result blocks wrapped in Claude "user" turns
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            if ("tool_result".equals(normalize(ci.optString("type")))) {
                String toolCallId = firstNonEmpty(ci, "tool_use_id", "tool_call_id", "id");
                String toolName = firstNonEmpty(ci, "tool_name", "name");
                String toolOutput = extractToolResultContent(ci);
                if (toolOutput.isEmpty()) {
                    return ParsedLine.raw(lineNumber, "");
                }
                return ParsedLine.toolResult(
                        lineNumber, toolName, toolOutput, rawDetails, toolCallId);
            }
        }
        // Then thinking
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            if ("thinking".equals(normalize(ci.optString("type")))) {
                String thinking = firstNonEmpty(ci, "thinking");
                return ParsedLine.thinking(lineNumber, thinking, rawDetails);
            }
        }
        // Default: extract all text content
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            if ("text".equals(ci.optString("type"))) {
                String t = ci.optString("text", "");
                if (!t.isEmpty()) {
                    if (textBuilder.length() > 0) textBuilder.append('\n');
                    textBuilder.append(t);
                }
            }
        }
        String cat = parentType.equals("assistant") ? "assistant" : "user";
        if (textBuilder.length() == 0) {
            return ParsedLine.raw(lineNumber, "");
        }
        return ParsedLine.message(
                lineNumber, cat, capitalize(cat), textBuilder.toString(), rawDetails);
    }

    private static ParsedLine classifyClaudeStreamEvent(
            long lineNumber, JSONObject event, String rawDetails) {
        String eventType = normalize(event.optString("type"));

        if (eventType.equals("content_block_start") || eventType.equals("content_block_delta")) {
            JSONObject contentBlock = event.optJSONObject("content_block");
            JSONObject delta = event.optJSONObject("delta");
            JSONObject source = contentBlock != null ? contentBlock : delta;
            if (source != null) {
                String blockType = normalize(source.optString("type"));
                if (blockType.contains("thinking")) {
                    return ParsedLine.thinking(
                            lineNumber, firstNonEmpty(source, "thinking", "text"), rawDetails);
                }
                if (blockType.contains("text")) {
                    return ParsedLine.message(
                            lineNumber,
                            "assistant",
                            "Assistant",
                            firstNonEmpty(source, "text"),
                            rawDetails);
                }
            }
        }
        if (eventType.equals("message_start")) {
            JSONObject message = event.optJSONObject("message");
            if (message != null) {
                String model = firstNonEmpty(message, "model");
                if (!model.isEmpty()) {
                    return ParsedLine.system(lineNumber, "System", "Model: " + model, rawDetails);
                }
            }
        }
        return ParsedLine.system(lineNumber, "Stream event", eventType, rawDetails);
    }

    private static ParsedLine classifyCodexItem(
            long lineNumber, String typeLower, JSONObject item, String rawDetails) {
        String itemType = normalize(item.optString("type"));
        String status = normalize(item.optString("status"));

        if (itemType.contains("reason")) {
            String itemText = extractText(item);
            if (itemText.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.thinking(lineNumber, itemText, rawDetails);
        }
        if (itemType.contains("agent_message") || itemType.contains("message")) {
            String itemText = extractText(item);
            if (itemText.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.message(lineNumber, "assistant", "Assistant", itemText, rawDetails);
        }
        if (itemType.contains("command_execution")
                || itemType.contains("mcp_tool_call")
                || itemType.contains("tool_call")
                || itemType.contains("tool")) {
            String toolCallId = firstNonEmpty(item, "id", "call_id", "tool_call_id");
            String toolName = extractCodexToolName(item, itemType);
            if (toolName.isEmpty() && itemType.contains("command_execution")) {
                toolName = "bash";
            }
            if (typeLower.contains("started") || status.contains("in_progress")) {
                String toolInput = extractCodexToolInput(item);
                if (toolInput.isEmpty()) {
                    return ParsedLine.raw(lineNumber, "");
                }
                return ParsedLine.toolCall(lineNumber, toolName, toolInput, rawDetails, toolCallId);
            }
            String toolOutput = extractCodexToolOutput(item);
            if (toolOutput.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.toolResult(lineNumber, toolName, toolOutput, rawDetails, toolCallId);
        }
        String itemText = extractText(item);
        if (itemText.isEmpty()) {
            return ParsedLine.raw(lineNumber, "");
        }
        return ParsedLine.system(lineNumber, "System", itemText, rawDetails);
    }

    private static ParsedLine classifyOpenCodePartEvent(
            long lineNumber, String typeLower, JSONObject part, String rawDetails) {
        String partType = normalize(part.optString("type"));

        if (typeLower.equals("text") || partType.equals("text")) {
            String text = firstNonEmpty(part, "text");
            if (text.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.message(lineNumber, "assistant", "Assistant", text, rawDetails);
        }

        if (typeLower.equals("tool_use") || partType.equals("tool")) {
            return classifyOpenCodeToolPart(lineNumber, part, rawDetails);
        }

        if (typeLower.equals("step_start")
                || typeLower.equals("step_finish")
                || partType.equals("step-start")
                || partType.equals("step-finish")) {
            return ParsedLine.raw(lineNumber, "");
        }

        String partText = extractText(part);
        if (partText.isEmpty()) {
            return ParsedLine.raw(lineNumber, "");
        }
        return ParsedLine.system(lineNumber, "System", partText, rawDetails);
    }

    private static ParsedLine classifyOpenCodeToolPart(
            long lineNumber, JSONObject part, String rawDetails) {
        String toolName = firstNonEmpty(part, "tool", "tool_name", "name");
        String toolCallId =
                firstNonEmpty(part, "callID", "callId", "call_id", "tool_call_id", "id");
        JSONObject state = part.optJSONObject("state");
        if (state == null) {
            return ParsedLine.raw(lineNumber, "");
        }

        String toolInput = extractToolInput(state.optJSONObject("input"), toolName);
        String toolOutput = extractOpenCodeToolOutput(state);
        String status = normalize(firstNonEmpty(state, "status"));

        if (!toolOutput.isEmpty()) {
            return ParsedLine.toolResult(lineNumber, toolName, toolOutput, rawDetails, toolCallId);
        }
        if ("completed".equals(status)) {
            return ParsedLine.raw(lineNumber, "");
        }
        if (toolInput.isEmpty()) {
            return ParsedLine.raw(lineNumber, "");
        }
        return ParsedLine.toolCall(lineNumber, toolName, toolInput, rawDetails, toolCallId);
    }

    private static ParsedLine classifyCursorToolCall(
            long lineNumber, JSONObject json, String rawDetails) {
        String subtype = normalize(firstNonEmpty(json, "subtype"));
        String callId = firstNonEmpty(json, "call_id", "tool_call_id", "tool_id");
        String toolName = extractCursorToolName(json);
        JSONObject tc = json.optJSONObject("tool_call");

        if (tc == null) {
            if (toolName.isEmpty()) {
                toolName = firstNonEmpty(json, "tool_name", "toolName", "name");
            }
            JSONObject parameters = json.optJSONObject("parameters");
            String input = extractToolInput(parameters, toolName);
            if (input.isEmpty()) {
                input = firstNonEmpty(json, "text", "input", "command");
            }
            if (input.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.toolCall(lineNumber, toolName, input, rawDetails, callId);
        }

        if (subtype.equals("completed")) {
            String output = extractCursorToolOutput(tc, toolName);
            return ParsedLine.toolResult(lineNumber, toolName, output, rawDetails, callId);
        }
        String input = extractCursorToolInput(tc, toolName);
        return ParsedLine.toolCall(lineNumber, toolName, input, rawDetails, callId);
    }

    private static ParsedLine classifyFallback(
            long lineNumber,
            String typeLower,
            String roleLower,
            JSONObject json,
            String rawDetails) {
        String text = extractText(json);
        boolean delta = json.optBoolean("delta", false);

        if (typeLower.contains("thinking") || typeLower.contains("reasoning")) {
            return ParsedLine.thinking(lineNumber, text, rawDetails);
        }
        if (isToolCall(typeLower, json)) {
            String toolCallId = firstNonEmpty(json, "tool_call_id", "call_id", "id");
            String toolName = firstNonEmpty(json, "tool_name", "toolName", "name");
            return ParsedLine.toolCall(lineNumber, toolName, text, rawDetails, toolCallId);
        }
        if (isToolResult(typeLower, json)) {
            String toolCallId = firstNonEmpty(json, "tool_call_id", "call_id", "id");
            String toolName = firstNonEmpty(json, "tool_name", "toolName", "name");
            return ParsedLine.toolResult(lineNumber, toolName, text, rawDetails, toolCallId);
        }
        if (roleLower.equals("assistant")
                || typeLower.contains("assistant")
                || typeLower.contains("agent_message")) {
            return ParsedLine.message(
                    lineNumber, "assistant", "Assistant", text, rawDetails, delta);
        }
        if (roleLower.equals("user") || typeLower.contains("user")) {
            return ParsedLine.message(lineNumber, "user", "User", text, rawDetails);
        }
        if (typeLower.contains("error") || json.has("error")) {
            return ParsedLine.message(lineNumber, "error", "Error", text, rawDetails);
        }
        return ParsedLine.system(lineNumber, "System", text, rawDetails);
    }

    // --- Tool input/output extraction ---

    private static String extractToolInput(JSONObject input, String toolName) {
        if (input == null) return "";
        String command = firstNonEmpty(input, "command");
        if (!command.isEmpty()) return command;
        String filePath = firstNonEmpty(input, "file_path", "filePath", "path");
        if (!filePath.isEmpty()) {
            String extra =
                    firstNonEmpty(input, "old_string", "oldString", "pattern", "text", "query");
            if (!extra.isEmpty()) {
                return filePath + " — " + excerpt(extra, 200);
            }
            return filePath;
        }
        String text = firstNonEmpty(input, "pattern", "text", "url", "query", "glob");
        if (!text.isEmpty()) return text;
        return input.toString(2);
    }

    private static String extractToolResultContent(JSONObject json) {
        Object contentObj = json.opt("content");
        if (contentObj instanceof String) {
            return (String) contentObj;
        }
        if (contentObj instanceof JSONArray) {
            return joinTextArray((JSONArray) contentObj);
        }
        String text = firstNonEmpty(json, "output", "text", "result");
        if (!text.isEmpty()) return text;
        return "";
    }

    private static String extractOpenCodeToolOutput(JSONObject state) {
        if (state == null) return "";

        Object outputObj = state.opt("output");
        if (outputObj instanceof String) {
            return (String) outputObj;
        }
        if (outputObj instanceof JSONArray) {
            return joinTextArray((JSONArray) outputObj);
        }
        if (outputObj instanceof JSONObject) {
            JSONObject output = (JSONObject) outputObj;
            String text = firstNonEmpty(output, "text", "content", "value", "stdout", "stderr");
            if (!text.isEmpty()) return text;
            if (!output.isEmpty()) return output.toString(2);
        }

        String text = firstNonEmpty(state, "stdout", "stderr", "result");
        if (!text.isEmpty()) return text;
        return "";
    }

    private static String extractCodexToolInput(JSONObject item) {
        String command = firstNonEmpty(item, "command");
        if (!command.isEmpty()) return command;

        JSONObject parameters = item.optJSONObject("parameters");
        if (parameters != null) {
            String parameterText = extractToolInput(parameters, firstNonEmpty(item, "name"));
            if (!parameterText.isEmpty()) return parameterText;
        }

        JSONObject arguments = item.optJSONObject("arguments");
        if (arguments != null) {
            String argumentText = extractToolInput(arguments, firstNonEmpty(item, "name"));
            if (!argumentText.isEmpty()) return argumentText;
            return arguments.toString(2);
        }

        String text = firstNonEmpty(item, "input", "query", "path", "url");
        if (!text.isEmpty()) return text;
        return extractText(item);
    }

    private static String extractCodexToolOutput(JSONObject item) {
        String output = firstNonEmpty(item, "aggregated_output", "output", "stdout", "stderr");
        if (!output.isEmpty()) return output;

        JSONObject result = item.optJSONObject("result");
        if (result != null) {
            output = firstNonEmpty(result, "output", "stdout", "stderr", "text", "result");
            if (!output.isEmpty()) return output;
            if (!result.isEmpty()) return result.toString(2);
        }

        if (item.containsKey("exit_code")) {
            int exitCode = item.optInt("exit_code");
            if (exitCode != 0) {
                return "Exit code: " + exitCode;
            }
        }
        return "";
    }

    private static String extractCodexToolName(JSONObject item, String itemType) {
        String toolName = firstNonEmpty(item, "tool_name", "toolName", "name");
        if (!toolName.isEmpty()) return toolName;
        if (itemType.contains("mcp")) return "mcp";
        return "";
    }

    private static String extractCursorToolInput(JSONObject tc, String toolName) {
        if (tc == null) return "";
        for (String key :
                new String[] {
                    "shellToolCall", "readToolCall", "writeToolCall", "editToolCall",
                    "globToolCall", "grepToolCall", "lsToolCall", "deleteToolCall",
                    "mcpToolCall", "semSearchToolCall"
                }) {
            JSONObject call = tc.optJSONObject(key);
            if (call == null) continue;
            JSONObject args = call.optJSONObject("args");
            if (args == null) continue;
            String cmd = firstNonEmpty(args, "command");
            if (!cmd.isEmpty()) return cmd;
            String path = firstNonEmpty(args, "path", "file_path");
            if (!path.isEmpty()) return path;
            String pattern = firstNonEmpty(args, "pattern", "glob");
            if (!pattern.isEmpty()) return pattern;
            return args.toString(2);
        }
        return "";
    }

    private static String extractCursorToolOutput(JSONObject tc, String toolName) {
        if (tc == null) return "";
        for (String key :
                new String[] {
                    "shellToolCall", "readToolCall", "writeToolCall", "editToolCall",
                    "globToolCall", "grepToolCall", "lsToolCall", "deleteToolCall",
                    "mcpToolCall", "semSearchToolCall"
                }) {
            JSONObject call = tc.optJSONObject(key);
            if (call == null) continue;
            Object resultObj = call.opt("result");
            if (resultObj instanceof String) return (String) resultObj;
            if (resultObj instanceof JSONObject) {
                JSONObject result = (JSONObject) resultObj;
                JSONObject success = result.optJSONObject("success");
                if (success != null) {
                    String stdout = success.optString("stdout", "");
                    String stderr = success.optString("stderr", "");
                    if (!stdout.isEmpty()) return stdout;
                    if (!stderr.isEmpty()) return stderr;
                }
                return result.toString(2);
            }
        }
        return "";
    }

    private static String extractCursorToolName(JSONObject json) {
        JSONObject tc = json.optJSONObject("tool_call");
        if (tc == null) return "";
        for (String key :
                new String[] {
                    "shellToolCall", "readToolCall", "writeToolCall", "editToolCall",
                    "globToolCall", "grepToolCall", "lsToolCall", "deleteToolCall",
                    "mcpToolCall", "semSearchToolCall"
                }) {
            if (tc.has(key)) {
                return key.replace("ToolCall", "").replace("Tool", "");
            }
        }
        return firstNonEmpty(tc, "name", "tool_name");
    }

    // --- Helpers ---

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean isToolCall(String typeLower, JSONObject json) {
        if (typeLower.contains("tool_call")) return true;
        if (typeLower.contains("tool") && typeLower.contains("start")) return true;
        if (json.has("tool_call") || json.has("tool_name") || json.has("toolName")) {
            return !isToolResult(typeLower, json);
        }
        return false;
    }

    private static boolean isToolResult(String typeLower, JSONObject json) {
        if (typeLower.contains("tool_result")) return true;
        if (typeLower.contains("tool")
                && (typeLower.contains("complete")
                        || typeLower.contains("result")
                        || typeLower.contains("response"))) {
            return true;
        }
        return json.has("tool_result") || json.has("tool_output");
    }

    private static String extractText(JSONObject json) {
        String text = firstNonEmpty(json, "text", "message", "content", "delta");
        if (!text.isEmpty()) return text;

        Object messageObj = json.opt("message");
        if (messageObj instanceof JSONObject) {
            text = firstNonEmpty((JSONObject) messageObj, "text", "content", "value");
            if (!text.isEmpty()) return text;
        }
        if (messageObj instanceof JSONArray) {
            return joinTextArray((JSONArray) messageObj);
        }

        Object contentObj = json.opt("content");
        if (contentObj instanceof JSONArray) {
            return joinTextArray((JSONArray) contentObj);
        }
        if (contentObj instanceof JSONObject) {
            text = firstNonEmpty((JSONObject) contentObj, "text", "content", "value");
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String joinTextArray(JSONArray array) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < array.size(); i++) {
            Object item = array.get(i);
            String text = null;
            if (item instanceof String) {
                text = (String) item;
            } else if (item instanceof JSONObject) {
                text = firstNonEmpty((JSONObject) item, "text", "content", "value");
            }
            if (text != null && !text.isEmpty()) {
                if (builder.length() > 0) builder.append(' ');
                builder.append(text);
            }
        }
        return builder.toString().trim();
    }

    private static JSONObject tryParseJson(String line) {
        if (!(line.startsWith("{") && line.endsWith("}"))) return null;
        try {
            return JSONObject.fromObject(line);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstNonEmpty(JSONObject json, String... keys) {
        for (String key : keys) {
            if (json == null || !json.containsKey(key)) continue;
            Object value = json.get(key);
            if (value == null) continue;
            if (value instanceof String) {
                String s = ((String) value).trim();
                if (!s.isEmpty()) return s;
            } else if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) {
                String s = String.valueOf(value).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return "";
    }

    private static String excerpt(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    private static String oneLine(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    // ---- Data classes ----

    static final class ParsedLine {
        private final long id;
        private final String category;
        private final String label;
        private final String content;
        private final String toolInput;
        private final String toolOutput;
        private final String toolName;
        private final String rawDetails;
        private final String toolCallId;
        private final boolean delta;
        private final Instant timestamp;

        private ParsedLine(
                long id,
                String category,
                String label,
                String content,
                String toolInput,
                String toolOutput,
                String toolName,
                String rawDetails,
                String toolCallId,
                boolean delta) {
            this.id = id;
            this.category = category;
            this.label = label;
            this.content = content;
            this.toolInput = toolInput;
            this.toolOutput = toolOutput;
            this.toolName = toolName;
            this.rawDetails = rawDetails;
            this.toolCallId = toolCallId;
            this.delta = delta;
            this.timestamp = Instant.now();
        }

        static ParsedLine raw(long id, String line) {
            return new ParsedLine(id, "raw", "", line, "", "", "", line, null, false);
        }

        static ParsedLine system(long id, String label, String content, String rawDetails) {
            return new ParsedLine(
                    id, "system", label, content, "", "", "", rawDetails, null, false);
        }

        static ParsedLine message(
                long id, String category, String label, String content, String rawDetails) {
            return new ParsedLine(
                    id, category, label, content, "", "", "", rawDetails, null, false);
        }

        static ParsedLine message(
                long id,
                String category,
                String label,
                String content,
                String rawDetails,
                boolean delta) {
            return new ParsedLine(
                    id, category, label, content, "", "", "", rawDetails, null, delta);
        }

        static ParsedLine result(
                long id, String category, String label, String content, String rawDetails) {
            return new ParsedLine(
                    id, category, label, content, "", "", "", rawDetails, null, false);
        }

        static ParsedLine thinking(long id, String content, String rawDetails) {
            return new ParsedLine(
                    id, "thinking", "Thinking", content, "", "", "", rawDetails, null, false);
        }

        static ParsedLine toolCall(
                long id, String toolName, String toolInput, String rawDetails, String toolCallId) {
            String displayName = toolName.isEmpty() ? "Tool" : toolName;
            return new ParsedLine(
                    id,
                    "tool_call",
                    displayName,
                    "",
                    toolInput,
                    "",
                    toolName,
                    rawDetails,
                    toolCallId,
                    false);
        }

        static ParsedLine toolResult(
                long id, String toolName, String toolOutput, String rawDetails, String toolCallId) {
            String displayName = toolName.isEmpty() ? "Tool" : toolName;
            return new ParsedLine(
                    id,
                    "tool_result",
                    displayName,
                    "",
                    "",
                    toolOutput,
                    toolName,
                    rawDetails,
                    toolCallId,
                    false);
        }

        boolean isToolCall() {
            return "tool_call".equals(category);
        }

        String getToolCallIdOrGenerated() {
            if (toolCallId != null && !toolCallId.trim().isEmpty()) return toolCallId.trim();
            return "tool-call-" + id;
        }

        String getToolName() {
            return toolName == null ? "" : toolName;
        }

        String getSummary() {
            if (!content.isEmpty()) {
                return label + ": " + excerpt(oneLine(content), 180);
            }
            if (!toolInput.isEmpty()) {
                return label + ": " + excerpt(oneLine(toolInput), 180);
            }
            if (!toolOutput.isEmpty()) {
                return label + " result: " + excerpt(oneLine(toolOutput), 180);
            }
            return label;
        }

        EventView toEventView() {
            return new EventView(
                    id,
                    category,
                    label,
                    content,
                    toolInput,
                    toolOutput,
                    rawDetails,
                    timestamp,
                    delta);
        }
    }

    /** Represents a single conversation event for rendering in the UI. */
    public static final class EventView {
        private final long id;
        private final String category;
        private final String label;
        private final String content;
        private final String toolInput;
        private final String toolOutput;
        private final String rawDetails;
        private final Instant timestamp;
        private final boolean delta;

        EventView(
                long id,
                String category,
                String label,
                String content,
                String toolInput,
                String toolOutput,
                String rawDetails,
                Instant timestamp,
                boolean delta) {
            this.id = id;
            this.category = category;
            this.label = label;
            this.content = content;
            this.toolInput = toolInput;
            this.toolOutput = toolOutput;
            this.rawDetails = rawDetails;
            this.timestamp = timestamp;
            this.delta = delta;
        }

        public long getId() {
            return id;
        }

        public String getCategory() {
            return category;
        }

        /** Short display label like "Assistant", "Bash", "Read", "Result (2.3s)". */
        public String getLabel() {
            return label;
        }

        /** Full text content for messages, results, and thinking. */
        public String getContent() {
            return content;
        }

        /** Tool input: command text, file path, etc. */
        public String getToolInput() {
            return toolInput;
        }

        /** Tool output excerpt. */
        public String getToolOutput() {
            return toolOutput;
        }

        /** Raw JSON for the detail drill-down. */
        public String getRawDetails() {
            return rawDetails;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public boolean isDelta() {
            return delta;
        }

        public String getCategoryLabel() {
            return category.replace('_', ' ').toUpperCase(Locale.ROOT);
        }

        /** One-line summary for progressive events API backwards compat. */
        public String getSummary() {
            if (!content.isEmpty()) {
                String compact = content.replaceAll("\\s+", " ").trim();
                if (compact.length() > 180) compact = compact.substring(0, 177) + "...";
                return label + ": " + compact;
            }
            if (!toolInput.isEmpty()) {
                String compact = toolInput.replaceAll("\\s+", " ").trim();
                if (compact.length() > 180) compact = compact.substring(0, 177) + "...";
                return label + ": " + compact;
            }
            return label;
        }

        public boolean isEmpty() {
            if ("raw".equals(category)) {
                return content == null || content.trim().isEmpty();
            }
            return false;
        }

        /** Whether this event should show its content directly (not behind a click). */
        public boolean isInlineContent() {
            return "assistant".equals(category)
                    || "user".equals(category)
                    || "result".equals(category)
                    || "error".equals(category);
        }

        /** Whether this event is a tool call or tool result. */
        public boolean isToolEvent() {
            return "tool_call".equals(category) || "tool_result".equals(category);
        }

        /** Returns content converted from markdown to basic HTML for display in Jelly. */
        public String getContentHtml() {
            return markdownToHtml(content);
        }

        static String markdownToHtml(String md) {
            if (md == null || md.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            String[] lines = md.split("\n", -1);
            boolean inCodeBlock = false;
            boolean inList = false;

            for (String line : lines) {
                if (line.startsWith("```")) {
                    if (inList) {
                        out.append("</ul>");
                        inList = false;
                    }
                    if (inCodeBlock) {
                        out.append("</code></pre>");
                        inCodeBlock = false;
                    } else {
                        out.append("<pre><code>");
                        inCodeBlock = true;
                    }
                    continue;
                }
                if (inCodeBlock) {
                    out.append(escHtml(line)).append('\n');
                    continue;
                }

                String trimmed = line.trim();

                if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    if (!inList) {
                        out.append("<ul>");
                        inList = true;
                    }
                    out.append("<li>").append(inlineMarkdown(trimmed.substring(2))).append("</li>");
                    continue;
                }
                if (inList) {
                    out.append("</ul>");
                    inList = false;
                }

                if (trimmed.startsWith("### ")) {
                    out.append("<strong>")
                            .append(inlineMarkdown(trimmed.substring(4)))
                            .append("</strong><br/>");
                } else if (trimmed.startsWith("## ")) {
                    out.append("<strong>")
                            .append(inlineMarkdown(trimmed.substring(3)))
                            .append("</strong><br/>");
                } else if (trimmed.startsWith("# ")) {
                    out.append("<strong>")
                            .append(inlineMarkdown(trimmed.substring(2)))
                            .append("</strong><br/>");
                } else if (trimmed.startsWith("---")) {
                    out.append("<hr/>");
                } else if (trimmed.isEmpty()) {
                    out.append("<br/>");
                } else {
                    out.append(inlineMarkdown(line)).append("<br/>");
                }
            }
            if (inCodeBlock) out.append("</code></pre>");
            if (inList) out.append("</ul>");

            String result = out.toString();
            while (result.endsWith("<br/>")) {
                result = result.substring(0, result.length() - 5);
            }
            return result;
        }

        private static String inlineMarkdown(String text) {
            String s = escHtml(text);
            s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
            s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
            s = s.replaceAll("__([^_]+)__", "<strong>$1</strong>");
            s = s.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
            s = s.replaceAll("_([^_]+)_", "<em>$1</em>");
            return s;
        }

        private static String escHtml(String text) {
            if (text == null) return "";
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }
    }
}
