package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.util.Locale;

/**
 * Shared utility methods used by agent-specific log format classes. Provides JSON field extraction,
 * text normalization, and tool input/output helpers.
 */
public final class LogFormatUtils {

    private LogFormatUtils() {}

    public static String firstNonEmpty(JSONObject json, String... keys) {
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

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String excerpt(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }

    public static String extractText(JSONObject json) {
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

    public static String joinTextArray(JSONArray array) {
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

    public static String extractToolInput(JSONObject input, String toolName) {
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

    public static String extractToolResultContent(JSONObject json) {
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

    public static boolean isToolCall(String typeLower, JSONObject json) {
        if (typeLower.contains("tool_call")) return true;
        if (typeLower.contains("tool") && typeLower.contains("start")) return true;
        if (json.has("tool_call") || json.has("tool_name") || json.has("toolName")) {
            return !isToolResult(typeLower, json);
        }
        return false;
    }

    public static boolean isToolResult(String typeLower, JSONObject json) {
        if (typeLower.contains("tool_result")) return true;
        if (typeLower.contains("tool")
                && (typeLower.contains("complete")
                        || typeLower.contains("result")
                        || typeLower.contains("response"))) {
            return true;
        }
        return json.has("tool_result") || json.has("tool_output");
    }
}
