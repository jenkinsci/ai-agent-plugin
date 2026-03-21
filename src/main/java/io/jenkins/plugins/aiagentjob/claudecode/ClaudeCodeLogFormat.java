package io.jenkins.plugins.aiagentjob.claudecode;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Format-specific log classification for Claude Code stream-json output. Handles content arrays
 * (tool_use, tool_result, thinking, text) and stream_event deltas.
 *
 * <p>Used by both {@link ClaudeCodeAgentHandler} and {@link
 * io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler GeminiCliAgentHandler} since they
 * share the same stream-json format.
 */
public final class ClaudeCodeLogFormat implements AiAgentLogFormat {

    public static final ClaudeCodeLogFormat INSTANCE = new ClaudeCodeLogFormat();

    private ClaudeCodeLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        String type = LogFormatUtils.firstNonEmpty(json, "type", "event", "kind", "subtype");
        String typeLower = LogFormatUtils.normalize(type);

        // assistant/user message with content array
        if (typeLower.equals("assistant") || typeLower.equals("user")) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONArray contentArr = message.optJSONArray("content");
                if (contentArr != null && contentArr.size() > 0) {
                    return classifyContentArray(
                            lineNumber, typeLower, contentArr, json.toString(2));
                }
            }
        }

        // stream_event (streaming deltas)
        if (typeLower.equals("stream_event")) {
            JSONObject event = json.optJSONObject("event");
            if (event != null) {
                return classifyStreamEvent(lineNumber, event, json.toString(2));
            }
        }

        // Not a Claude Code specific format — let fallback handle it
        return null;
    }

    public static AiAgentLogParser.ParsedLine classifyContentArray(
            long lineNumber, String parentType, JSONArray contentArr, String rawDetails) {
        // Scan for tool_use first
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            String ciType = LogFormatUtils.normalize(ci.optString("type"));

            if (ciType.equals("tool_use")) {
                String toolName = LogFormatUtils.firstNonEmpty(ci, "name");
                String toolCallId = LogFormatUtils.firstNonEmpty(ci, "id");
                String toolInput =
                        LogFormatUtils.extractToolInput(ci.optJSONObject("input"), toolName);
                return AiAgentLogParser.ParsedLine.toolCall(
                        lineNumber, toolName, toolInput, rawDetails, toolCallId);
            }
        }
        // Then tool_result blocks wrapped in Claude "user" turns
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            if ("tool_result".equals(LogFormatUtils.normalize(ci.optString("type")))) {
                String toolCallId =
                        LogFormatUtils.firstNonEmpty(ci, "tool_use_id", "tool_call_id", "id");
                String toolName = LogFormatUtils.firstNonEmpty(ci, "tool_name", "name");
                String toolOutput = LogFormatUtils.extractToolResultContent(ci);
                if (toolOutput.isEmpty()) {
                    return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
                }
                return AiAgentLogParser.ParsedLine.toolResult(
                        lineNumber, toolName, toolOutput, rawDetails, toolCallId);
            }
        }
        // Then thinking
        for (int i = 0; i < contentArr.size(); i++) {
            Object obj = contentArr.get(i);
            if (!(obj instanceof JSONObject)) continue;
            JSONObject ci = (JSONObject) obj;
            if ("thinking".equals(LogFormatUtils.normalize(ci.optString("type")))) {
                String thinking = LogFormatUtils.firstNonEmpty(ci, "thinking");
                return AiAgentLogParser.ParsedLine.thinking(lineNumber, thinking, rawDetails);
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
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.message(
                lineNumber,
                cat,
                LogFormatUtils.capitalize(cat),
                textBuilder.toString(),
                rawDetails);
    }

    public static AiAgentLogParser.ParsedLine classifyStreamEvent(
            long lineNumber, JSONObject event, String rawDetails) {
        String eventType = LogFormatUtils.normalize(event.optString("type"));

        if (eventType.equals("content_block_start") || eventType.equals("content_block_delta")) {
            JSONObject contentBlock = event.optJSONObject("content_block");
            JSONObject delta = event.optJSONObject("delta");
            JSONObject source = contentBlock != null ? contentBlock : delta;
            if (source != null) {
                String blockType = LogFormatUtils.normalize(source.optString("type"));
                if (blockType.contains("thinking")) {
                    return AiAgentLogParser.ParsedLine.thinking(
                            lineNumber,
                            LogFormatUtils.firstNonEmpty(source, "thinking", "text"),
                            rawDetails);
                }
                if (blockType.contains("text")) {
                    return AiAgentLogParser.ParsedLine.message(
                            lineNumber,
                            "assistant",
                            "Assistant",
                            LogFormatUtils.firstNonEmpty(source, "text"),
                            rawDetails);
                }
            }
        }
        if (eventType.equals("message_start")) {
            JSONObject message = event.optJSONObject("message");
            if (message != null) {
                String model = LogFormatUtils.firstNonEmpty(message, "model");
                if (!model.isEmpty()) {
                    return AiAgentLogParser.ParsedLine.system(
                            lineNumber, "System", "Model: " + model, rawDetails);
                }
            }
        }
        return AiAgentLogParser.ParsedLine.system(
                lineNumber, "Stream event", eventType, rawDetails);
    }
}
