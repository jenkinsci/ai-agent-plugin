package io.jenkins.plugins.aiagentjob.kiro;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Classifies Kiro CLI output: session JSONL, ACP JSON-RPC, and plain-text headless lines. */
public final class KiroLogFormat implements AiAgentLogFormat {
    public static final KiroLogFormat INSTANCE = new KiroLogFormat();

    private KiroLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        List<AiAgentLogParser.ParsedLine> parsed = classifyAll(lineNumber, json);
        return parsed == null || parsed.isEmpty() ? null : parsed.get(0);
    }

    @Override
    public List<AiAgentLogParser.ParsedLine> classifyAll(long lineNumber, JSONObject json) {
        String kind = LogFormatUtils.normalize(json.optString("kind", ""));
        if (!kind.isEmpty()) {
            return classifySessionJsonl(lineNumber, json);
        }
        String method = json.optString("method", "");
        if (!method.isEmpty()) {
            return classifyAcpNotification(lineNumber, json);
        }
        String type = LogFormatUtils.normalize(json.optString("type", ""));
        if (!type.isEmpty()) {
            return classifyTypedJson(lineNumber, json, type);
        }
        return null;
    }

    private static List<AiAgentLogParser.ParsedLine> classifySessionJsonl(
            long lineNumber, JSONObject json) {
        String kind = LogFormatUtils.normalize(json.optString("kind", ""));
        String rawDetails = json.toString(2);
        if ("prompt".equals(kind)) {
            return classifyPrompt(lineNumber, json, rawDetails);
        }
        if ("assistantmessage".equals(kind)) {
            return classifyAssistantMessage(lineNumber, json, rawDetails);
        }
        if ("toolresults".equals(kind)) {
            return classifyToolResults(lineNumber, json, rawDetails);
        }
        return null;
    }

    private static List<AiAgentLogParser.ParsedLine> classifyPrompt(
            long lineNumber, JSONObject json, String rawDetails) {
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        String text = extractSessionText(data.optJSONArray("content"));
        if (text.isEmpty()) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        return List.of(
                AiAgentLogParser.ParsedLine.message(lineNumber, "user", "User", text, rawDetails));
    }

    private static List<AiAgentLogParser.ParsedLine> classifyAssistantMessage(
            long lineNumber, JSONObject json, String rawDetails) {
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        List<AiAgentLogParser.ParsedLine> events = new ArrayList<>();
        JSONArray content = data.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object item = content.get(i);
                if (!(item instanceof JSONObject)) continue;
                JSONObject block = (JSONObject) item;
                String blockKind = LogFormatUtils.normalize(block.optString("kind", ""));
                if ("text".equals(blockKind)) {
                    Object dataValue = block.opt("data");
                    String text = "";
                    if (dataValue instanceof String) {
                        text = (String) dataValue;
                    } else if (dataValue instanceof JSONObject) {
                        text = ((JSONObject) dataValue).optString("data", "");
                    }
                    if (!text.isEmpty()) {
                        events.add(
                                AiAgentLogParser.ParsedLine.message(
                                        lineNumber,
                                        "assistant",
                                        "Assistant",
                                        text,
                                        rawDetails,
                                        true));
                    }
                } else if ("tooluse".equals(blockKind)) {
                    JSONObject toolData = block.optJSONObject("data");
                    if (toolData == null) continue;
                    String toolName = toolData.optString("name", "Tool");
                    String toolCallId = toolData.optString("toolUseId", "");
                    JSONObject input = toolData.optJSONObject("input");
                    String inputText = LogFormatUtils.extractToolInput(input, toolName);
                    if (inputText.isEmpty()) {
                        inputText = LogFormatUtils.firstNonEmpty(toolData, "title");
                    }
                    if (!inputText.isEmpty()) {
                        events.add(
                                AiAgentLogParser.ParsedLine.toolCall(
                                        lineNumber, toolName, inputText, rawDetails, toolCallId));
                    }
                }
            }
        }
        if (events.isEmpty()) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        return events;
    }

    private static List<AiAgentLogParser.ParsedLine> classifyToolResults(
            long lineNumber, JSONObject json, String rawDetails) {
        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        List<AiAgentLogParser.ParsedLine> events = new ArrayList<>();
        JSONArray content = data.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                Object item = content.get(i);
                if (!(item instanceof JSONObject)) continue;
                JSONObject block = (JSONObject) item;
                String blockKind = LogFormatUtils.normalize(block.optString("kind", ""));
                if ("toolresult".equals(blockKind)) {
                    JSONObject resultData = block.optJSONObject("data");
                    if (resultData == null) continue;
                    String toolCallId = resultData.optString("toolUseId", "");
                    String status = resultData.optString("status", "");
                    JSONArray resultContent = resultData.optJSONArray("content");
                    String output = extractSessionText(resultContent);
                    if (output.isEmpty()) {
                        output = status;
                    }
                    String toolName = inferToolNameFromId(toolCallId, json);
                    events.add(
                            AiAgentLogParser.ParsedLine.toolResult(
                                    lineNumber, toolName, output, rawDetails, toolCallId));
                }
            }
        }
        if (events.isEmpty()) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        return events;
    }

    private static List<AiAgentLogParser.ParsedLine> classifyAcpNotification(
            long lineNumber, JSONObject json) {
        String rawDetails = json.toString(2);
        String method = json.optString("method", "");
        if (!"session/update".equals(method)) {
            return null;
        }
        JSONObject params = json.optJSONObject("params");
        JSONObject update = params == null ? null : params.optJSONObject("update");
        if (update == null) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        return classifyAcpUpdate(lineNumber, update, rawDetails);
    }

    private static List<AiAgentLogParser.ParsedLine> classifyAcpUpdate(
            long lineNumber, JSONObject update, String rawDetails) {
        String updateType = LogFormatUtils.normalize(update.optString("sessionUpdate", ""));
        JSONObject content = update.optJSONObject("content");
        String text = extractChunkText(content);

        if ("agent_message_chunk".equals(updateType)) {
            return List.of(
                    AiAgentLogParser.ParsedLine.message(
                            lineNumber, "assistant", "Assistant", text, rawDetails, true));
        }
        if ("user_message_chunk".equals(updateType)) {
            return List.of(
                    AiAgentLogParser.ParsedLine.message(
                            lineNumber, "user", "User", text, rawDetails));
        }
        if ("agent_thought_chunk".equals(updateType)) {
            return List.of(
                    AiAgentLogParser.ParsedLine.thinking(lineNumber, text, rawDetails, true));
        }
        if ("tool_call".equals(updateType)) {
            return classifyAcpToolCall(lineNumber, update, rawDetails);
        }
        if ("tool_call_update".equals(updateType)) {
            return classifyAcpToolUpdate(lineNumber, update, rawDetails);
        }
        return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
    }

    private static List<AiAgentLogParser.ParsedLine> classifyAcpToolCall(
            long lineNumber, JSONObject update, String rawDetails) {
        String toolName = LogFormatUtils.firstNonEmpty(update, "kind", "title");
        String toolCallId =
                LogFormatUtils.firstNonEmpty(update, "toolCallId", "tool_call_id", "id");
        String input = LogFormatUtils.extractToolInput(update.optJSONObject("rawInput"), toolName);
        if (input.isEmpty()) {
            input = LogFormatUtils.firstNonEmpty(update, "title");
        }
        if (input.isEmpty()) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        return List.of(
                AiAgentLogParser.ParsedLine.toolCall(
                        lineNumber, toolName, input, rawDetails, toolCallId));
    }

    private static List<AiAgentLogParser.ParsedLine> classifyAcpToolUpdate(
            long lineNumber, JSONObject update, String rawDetails) {
        String status = LogFormatUtils.normalize(update.optString("status", ""));
        if (!"completed".equals(status) && !"failed".equals(status)) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        String toolName = LogFormatUtils.firstNonEmpty(update, "kind", "title");
        String toolCallId =
                LogFormatUtils.firstNonEmpty(update, "toolCallId", "tool_call_id", "id");
        String output = extractAcpToolOutput(update);
        if (output.isEmpty()) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, ""));
        }
        return List.of(
                AiAgentLogParser.ParsedLine.toolResult(
                        lineNumber, toolName, output, rawDetails, toolCallId));
    }

    private static List<AiAgentLogParser.ParsedLine> classifyTypedJson(
            long lineNumber, JSONObject json, String typeLower) {
        if (typeLower.equals("init")) {
            String model = LogFormatUtils.firstNonEmpty(json, "model");
            if (!model.isEmpty()) {
                String rawDetails = json.toString(2);
                return List.of(
                        AiAgentLogParser.ParsedLine.system(
                                lineNumber, "System", "Model: " + model, rawDetails));
            }
        }
        return null;
    }

    @Override
    public List<AiAgentLogParser.ParsedLine> classifyRaw(long lineNumber, String line) {
        String stripped = stripAnsi(line);
        if (stripped.isEmpty()) {
            return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, line));
        }
        int prefixIdx = stripped.indexOf("> ");
        if (prefixIdx >= 0) {
            String content = stripped.substring(prefixIdx + 2);
            if (content.isEmpty()) {
                return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, line));
            }
            return List.of(
                    AiAgentLogParser.ParsedLine.message(
                            lineNumber, "assistant", "Assistant", content, line, false));
        }
        return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, line));
    }

    private static String extractSessionText(JSONArray contentArray) {
        if (contentArray == null) return "";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < contentArray.size(); i++) {
            Object item = contentArray.get(i);
            if (!(item instanceof JSONObject)) continue;
            JSONObject block = (JSONObject) item;
            if (!"text".equals(LogFormatUtils.normalize(block.optString("kind", "")))) continue;
            Object dataValue = block.opt("data");
            String data = "";
            if (dataValue instanceof String) {
                data = (String) dataValue;
            } else if (dataValue instanceof JSONObject) {
                data = ((JSONObject) dataValue).optString("data", "");
            }
            if (!data.isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(data);
            }
        }
        return text.toString().trim();
    }

    private static String extractChunkText(JSONObject content) {
        if (content == null) return "";
        Object text = content.opt("text");
        if (text instanceof String) {
            return (String) text;
        }
        return LogFormatUtils.extractText(content);
    }

    private static String extractAcpToolOutput(JSONObject update) {
        JSONArray content = update.optJSONArray("content");
        if (content != null) {
            StringBuilder output = new StringBuilder();
            for (int i = 0; i < content.size(); i++) {
                Object item = content.get(i);
                if (!(item instanceof JSONObject)) continue;
                JSONObject element = (JSONObject) item;
                String text = extractChunkText(element);
                if (!text.isEmpty()) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(text);
                }
            }
            if (output.length() > 0) {
                return output.toString();
            }
        }

        Object rawOutput = update.opt("rawOutput");
        if (rawOutput instanceof String) {
            return (String) rawOutput;
        }
        if (rawOutput instanceof JSONObject && !((JSONObject) rawOutput).isEmpty()) {
            return ((JSONObject) rawOutput).toString(2);
        }
        return "";
    }

    private static String inferToolNameFromId(String toolCallId, JSONObject json) {
        if (toolCallId == null || toolCallId.isEmpty()) return "Tool";
        JSONObject data = json.optJSONObject("data");
        if (data == null) return "Tool";
        JSONObject results = data.optJSONObject("results");
        if (results == null) return "Tool";
        JSONObject result = results.optJSONObject(toolCallId);
        if (result == null) return "Tool";
        JSONObject tool = result.optJSONObject("tool");
        if (tool == null) return "Tool";
        JSONObject builtIn = tool.optJSONObject("kind");
        if (builtIn == null) return "Tool";
        return builtIn.keys().hasNext() ? builtIn.keys().next() : "Tool";
    }

    private static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("(?:\u001b)?\\[[0-9;]*m", "");
    }
}
