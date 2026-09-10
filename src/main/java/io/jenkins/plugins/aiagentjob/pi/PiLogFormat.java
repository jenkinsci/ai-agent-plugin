package io.jenkins.plugins.aiagentjob.pi;

import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentLogParser.ParsedLine;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Classifies Pi's JSON print-mode events, not its persisted session entries or RPC responses. */
public final class PiLogFormat implements AiAgentLogFormat {
    public static final PiLogFormat INSTANCE = new PiLogFormat();

    private static final Set<String> HIDDEN_EVENTS =
            Set.of(
                    "session",
                    "agent_start",
                    "agent_end",
                    "agent_settled",
                    "turn_start",
                    "turn_end",
                    "message_start",
                    "tool_execution_update",
                    "entry_appended",
                    "queue_update",
                    "session_info_changed",
                    "thinking_level_changed",
                    "compaction_start",
                    "compaction_end",
                    "auto_retry_start",
                    "auto_retry_end");

    private PiLogFormat() {}

    static boolean isUsageSnapshot(String type) {
        return HIDDEN_EVENTS.contains(type) || "message_update".equals(type);
    }

    @Override
    public ParsedLine classify(long lineNumber, JSONObject json) {
        List<ParsedLine> lines = classifyAll(lineNumber, json);
        return lines == null || lines.isEmpty() ? null : lines.get(0);
    }

    @Override
    public List<ParsedLine> classifyAll(long lineNumber, JSONObject json) {
        String type = json.optString("type");
        String raw = json.toString(2);
        if ("message_update".equals(type)) {
            JSONObject event = json.optJSONObject("assistantMessageEvent");
            JSONObject message = json.optJSONObject("message");
            if (event == null || message == null) return List.of();
            String eventType = event.optString("type");
            if ("text_end".equals(eventType) || "thinking_end".equals(eventType)) {
                boolean thinking = "thinking_end".equals(eventType);
                String text = event.optString("content");
                ParsedLine line =
                        thinking
                                ? ParsedLine.thinking(lineNumber, text, raw)
                                : ParsedLine.message(
                                        lineNumber, "assistant", "Assistant", text, raw);
                return List.of(
                        line.withDeduplicationKey(
                                contentKey(message, event.optInt("contentIndex", 0))));
            }
            if ("toolcall_end".equals(eventType)) {
                JSONObject tool = event.optJSONObject("toolCall");
                return tool == null ? List.of() : List.of(toolCall(lineNumber, tool, raw));
            }
            return List.of();
        }
        if ("message_end".equals(type)) {
            JSONObject message = json.optJSONObject("message");
            return message == null ? List.of() : message(lineNumber, message, raw);
        }
        if ("tool_execution_start".equals(type)) {
            return List.of(
                    ParsedLine.toolCall(
                                    lineNumber,
                                    json.optString("toolName"),
                                    arguments(json.optJSONObject("args")),
                                    raw,
                                    json.optString("toolCallId"))
                            .withDeduplicationKey(toolKey("call", json.optString("toolCallId"))));
        }
        if ("tool_execution_end".equals(type)) {
            JSONObject result = json.optJSONObject("result");
            return List.of(
                    toolResult(
                            lineNumber,
                            json.optString("toolName"),
                            json.optString("toolCallId"),
                            result == null ? "" : text(result.opt("content")),
                            json.optBoolean("isError", false),
                            raw));
        }
        return HIDDEN_EVENTS.contains(type) ? List.of() : null;
    }

    private static List<ParsedLine> message(long number, JSONObject message, String raw) {
        String role = message.optString("role");
        if ("user".equals(role)) {
            return List.of(
                    ParsedLine.message(number, "user", "User", text(message.opt("content")), raw));
        }
        if ("toolResult".equals(role)) {
            return List.of(
                    toolResult(
                            number,
                            message.optString("toolName"),
                            message.optString("toolCallId"),
                            text(message.opt("content")),
                            message.optBoolean("isError", false),
                            raw));
        }
        if (!"assistant".equals(role)) return List.of();
        List<ParsedLine> lines = new ArrayList<>();
        JSONArray content = message.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                JSONObject block = content.optJSONObject(i);
                if (block == null) continue;
                String type = block.optString("type");
                if ("text".equals(type)) {
                    lines.add(
                            ParsedLine.message(
                                            number,
                                            "assistant",
                                            "Assistant",
                                            block.optString("text"),
                                            raw)
                                    .withDeduplicationKey(contentKey(message, i)));
                } else if ("thinking".equals(type)) {
                    lines.add(
                            ParsedLine.thinking(number, block.optString("thinking"), raw)
                                    .withDeduplicationKey(contentKey(message, i)));
                } else if ("toolCall".equals(type)) {
                    lines.add(toolCall(number, block, raw));
                }
            }
        }
        String reason = message.optString("stopReason");
        if ("error".equals(reason) || "aborted".equals(reason)) {
            String error = LogFormatUtils.firstNonEmpty(message, "errorMessage");
            lines.add(
                    ParsedLine.message(
                            number,
                            "error",
                            "Error",
                            error.isEmpty() ? "Request " + reason : error,
                            raw));
        }
        return lines;
    }

    private static ParsedLine toolCall(long number, JSONObject tool, String raw) {
        String id = tool.optString("id");
        return ParsedLine.toolCall(
                        number,
                        tool.optString("name"),
                        arguments(tool.optJSONObject("arguments")),
                        raw,
                        id)
                .withDeduplicationKey(toolKey("call", id));
    }

    private static ParsedLine toolResult(
            long number, String name, String id, String output, boolean error, String raw) {
        return ParsedLine.toolResult(number, name, error ? "Error: " + output : output, raw, id)
                .withDeduplicationKey(toolKey("result", id));
    }

    private static String arguments(JSONObject args) {
        return args == null ? "" : args.toString(2);
    }

    private static String contentKey(JSONObject message, int index) {
        return message.has("timestamp")
                ? "pi-content:" + message.optLong("timestamp") + ':' + index
                : null;
    }

    private static String toolKey(String kind, String id) {
        return id.isEmpty() ? null : "pi-tool-" + kind + ':' + id;
    }

    private static String text(Object content) {
        if (content instanceof String) return (String) content;
        if (!(content instanceof JSONArray)) return "";
        List<String> parts = new ArrayList<>();
        JSONArray blocks = (JSONArray) content;
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            if ("text".equals(block.optString("type"))) {
                parts.add(block.optString("text"));
            } else if ("image".equals(block.optString("type"))) {
                parts.add("[Image: " + block.optString("mimeType", "image") + ']');
            }
        }
        return String.join("\n", parts);
    }
}
