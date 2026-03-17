package io.jenkins.plugins.aiagentjob.opencode;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Format-specific log classification for OpenCode JSONL output. Handles part-based events for text
 * and tool use with nested state objects.
 */
public final class OpenCodeLogFormat implements AiAgentLogFormat {

    public static final OpenCodeLogFormat INSTANCE = new OpenCodeLogFormat();

    private OpenCodeLogFormat() {}

    @Override
    public AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json) {
        String type = LogFormatUtils.firstNonEmpty(json, "type", "event", "kind", "subtype");
        String typeLower = LogFormatUtils.normalize(type);

        // OpenCode uses part-based events
        if (typeLower.equals("step_start")
                || typeLower.equals("step_finish")
                || typeLower.equals("tool_use")
                || typeLower.equals("text")) {
            JSONObject part = json.optJSONObject("part");
            if (part != null) {
                return classifyPartEvent(lineNumber, typeLower, part, json.toString(2));
            }
        }

        return null;
    }

    static AiAgentLogParser.ParsedLine classifyPartEvent(
            long lineNumber, String typeLower, JSONObject part, String rawDetails) {
        String partType = LogFormatUtils.normalize(part.optString("type"));

        if (typeLower.equals("text") || partType.equals("text")) {
            String text = LogFormatUtils.firstNonEmpty(part, "text");
            if (text.isEmpty()) {
                return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
            }
            return AiAgentLogParser.ParsedLine.message(
                    lineNumber, "assistant", "Assistant", text, rawDetails);
        }

        if (typeLower.equals("tool_use") || partType.equals("tool")) {
            return classifyToolPart(lineNumber, part, rawDetails);
        }

        if (typeLower.equals("step_finish") || partType.equals("step-finish")) {
            String reason = LogFormatUtils.firstNonEmpty(part, "reason");
            if ("stop".equals(reason) || "end_turn".equals(reason)) {
                JSONObject tokens = part.optJSONObject("tokens");
                StringBuilder info = new StringBuilder();
                info.append(LogFormatUtils.capitalize(reason));
                if (tokens != null) {
                    String total = LogFormatUtils.firstNonEmpty(tokens, "total");
                    if (!total.isEmpty()) {
                        info.append(" (").append(total).append(" tokens)");
                    }
                }
                return AiAgentLogParser.ParsedLine.result(
                        lineNumber, "result", "Result", info.toString(), rawDetails);
            }
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }

        if (typeLower.equals("step_start") || partType.equals("step-start")) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }

        String partText = LogFormatUtils.extractText(part);
        if (partText.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.system(lineNumber, "System", partText, rawDetails);
    }

    static AiAgentLogParser.ParsedLine classifyToolPart(
            long lineNumber, JSONObject part, String rawDetails) {
        String toolName = LogFormatUtils.firstNonEmpty(part, "tool", "tool_name", "name");
        String toolCallId =
                LogFormatUtils.firstNonEmpty(
                        part, "callID", "callId", "call_id", "tool_call_id", "id");
        JSONObject state = part.optJSONObject("state");
        if (state == null) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }

        String toolInput = LogFormatUtils.extractToolInput(state.optJSONObject("input"), toolName);
        String toolOutput = extractToolOutput(state);
        String status = LogFormatUtils.normalize(LogFormatUtils.firstNonEmpty(state, "status"));

        if (!toolOutput.isEmpty()) {
            return AiAgentLogParser.ParsedLine.toolResult(
                    lineNumber, toolName, toolOutput, rawDetails, toolCallId);
        }
        if ("completed".equals(status)) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        if (toolInput.isEmpty()) {
            return AiAgentLogParser.ParsedLine.raw(lineNumber, "");
        }
        return AiAgentLogParser.ParsedLine.toolCall(
                lineNumber, toolName, toolInput, rawDetails, toolCallId);
    }

    static String extractToolOutput(JSONObject state) {
        if (state == null) return "";

        Object outputObj = state.opt("output");
        if (outputObj instanceof String) {
            return (String) outputObj;
        }
        if (outputObj instanceof JSONArray) {
            return LogFormatUtils.joinTextArray((JSONArray) outputObj);
        }
        if (outputObj instanceof JSONObject) {
            JSONObject output = (JSONObject) outputObj;
            String text =
                    LogFormatUtils.firstNonEmpty(
                            output, "text", "content", "value", "stdout", "stderr");
            if (!text.isEmpty()) return text;
            if (!output.isEmpty()) return output.toString(2);
        }

        String text = LogFormatUtils.firstNonEmpty(state, "stdout", "stderr", "result");
        if (!text.isEmpty()) return text;
        return "";
    }
}
