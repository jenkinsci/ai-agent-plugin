package io.jenkins.plugins.aiagentjob;

import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeLogFormat;

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
 * Parses JSONL output from AI agents into classified {@link EventView} objects.
 *
 * <p>Each {@link AiAgentTypeHandler} may supply its own {@link AiAgentLogFormat} via {@link
 * AiAgentTypeHandler#getLogFormat()}. When a format is provided, the parser first delegates to it;
 * if the format returns {@code null} (unrecognised line), the parser falls through to the shared
 * format and the generic fallback.
 *
 * <p>Built-in formats:
 *
 * <ul>
 *   <li>Claude Code log format — Claude Code / Gemini CLI stream-json
 *   <li>Codex log format — Codex CLI JSONL
 *   <li>Cursor log format — Cursor Agent stream-json
 *   <li>OpenCode log format — OpenCode JSONL
 * </ul>
 *
 * Shared helpers live in {@link LogFormatUtils}.
 */
public final class AiAgentLogParser {

    private AiAgentLogParser() {}

    public static List<EventView> parse(File rawLogFile) throws IOException {
        return parse(rawLogFile, null);
    }

    public static List<EventView> parse(File rawLogFile, AiAgentLogFormat format)
            throws IOException {
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
                EventView ev = parseLine(idx, line, format).toEventView();
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
                    continue;
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

    public static ParsedLine parseLine(long lineNumber, String line) {
        return parseLine(lineNumber, line, null);
    }

    public static ParsedLine parseLine(long lineNumber, String line, AiAgentLogFormat format) {
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
        return classifyJson(lineNumber, json, format);
    }

    private static ParsedLine classifyJson(
            long lineNumber, JSONObject json, AiAgentLogFormat format) {
        // 1) Try the handler-specific format first
        if (format != null) {
            ParsedLine result = format.classify(lineNumber, json);
            if (result != null) {
                return result;
            }
        }

        // 2) Shared format: common types across multiple agents
        return classifySharedFormat(lineNumber, json);
    }

    /**
     * Shared log format that handles common JSON types used by multiple agents. This is the
     * fallback when the handler-specific format returns {@code null} or when no format is provided.
     */
    private static ParsedLine classifySharedFormat(long lineNumber, JSONObject json) {
        String type = LogFormatUtils.firstNonEmpty(json, "type", "event", "kind", "subtype");
        String role = LogFormatUtils.firstNonEmpty(json, "role");
        String typeLower = LogFormatUtils.normalize(type);
        String roleLower = LogFormatUtils.normalize(role);
        String rawDetails = json.toString(2);

        // --- Common shared types ---

        if (typeLower.equals("system")) {
            String subtype =
                    LogFormatUtils.normalize(LogFormatUtils.firstNonEmpty(json, "subtype"));
            String modelField = LogFormatUtils.firstNonEmpty(json, "model");
            String label = "System" + (!subtype.isEmpty() ? " " + subtype : "");
            String content =
                    !modelField.isEmpty()
                            ? "Model: " + modelField
                            : LogFormatUtils.extractText(json);
            return ParsedLine.system(lineNumber, label, content, rawDetails);
        }

        if (typeLower.equals("result")) {
            String resultText = LogFormatUtils.firstNonEmpty(json, "result", "error");
            boolean isError = json.optBoolean("is_error", false);
            if (resultText.isEmpty()) {
                String status = LogFormatUtils.firstNonEmpty(json, "status", "subtype");
                if (!status.isEmpty()) {
                    resultText = LogFormatUtils.capitalize(status);
                }
            }
            if (resultText.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            String durationMs = LogFormatUtils.firstNonEmpty(json, "duration_ms");
            if (durationMs.isEmpty()) {
                JSONObject stats = json.optJSONObject("stats");
                if (stats != null) {
                    durationMs = LogFormatUtils.firstNonEmpty(stats, "duration_ms");
                }
            }
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
            String modelField = LogFormatUtils.firstNonEmpty(json, "model");
            if (!modelField.isEmpty()) {
                return ParsedLine.system(lineNumber, "System", "Model: " + modelField, rawDetails);
            }
            String initText = LogFormatUtils.extractText(json);
            if (initText.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.system(lineNumber, "System", initText, rawDetails);
        }

        // --- assistant/user message (fallback for bare messages without content array) ---

        if (typeLower.equals("assistant") || typeLower.equals("user")) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONArray contentArr = message.optJSONArray("content");
                if (contentArr != null && contentArr.size() > 0) {
                    return ClaudeCodeLogFormat.classifyContentArray(
                            lineNumber, typeLower, contentArr, rawDetails);
                }
                String msgText = LogFormatUtils.extractText(message);
                String cat = typeLower.equals("assistant") ? "assistant" : "user";
                return ParsedLine.message(
                        lineNumber, cat, LogFormatUtils.capitalize(cat), msgText, rawDetails);
            }
        }

        // --- Standalone tool_use ---

        if (typeLower.equals("tool_use")) {
            String toolName = LogFormatUtils.firstNonEmpty(json, "tool_name", "name");
            String toolCallId = LogFormatUtils.firstNonEmpty(json, "tool_id", "id", "tool_call_id");
            JSONObject toolParameters = json.optJSONObject("input");
            if (toolParameters == null) {
                toolParameters = json.optJSONObject("parameters");
            }
            String toolInput = LogFormatUtils.extractToolInput(toolParameters, toolName);
            if (toolInput.isEmpty()) {
                toolInput = LogFormatUtils.extractText(json);
            }
            if (toolInput.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.toolCall(lineNumber, toolName, toolInput, rawDetails, toolCallId);
        }

        // --- Standalone tool_result ---

        if (typeLower.equals("tool_result")) {
            String toolCallId = LogFormatUtils.firstNonEmpty(json, "tool_id", "tool_call_id", "id");
            String toolName = LogFormatUtils.firstNonEmpty(json, "tool_name", "name");
            String output = LogFormatUtils.extractToolResultContent(json);
            if (output.isEmpty()) {
                return ParsedLine.raw(lineNumber, "");
            }
            return ParsedLine.toolResult(lineNumber, toolName, output, rawDetails, toolCallId);
        }

        // --- Generic fallback ---

        return classifyFallback(lineNumber, typeLower, roleLower, json, rawDetails);
    }

    private static ParsedLine classifyFallback(
            long lineNumber,
            String typeLower,
            String roleLower,
            JSONObject json,
            String rawDetails) {
        String text = LogFormatUtils.extractText(json);
        boolean delta = json.optBoolean("delta", false);

        if (typeLower.contains("thinking") || typeLower.contains("reasoning")) {
            return ParsedLine.thinking(lineNumber, text, rawDetails);
        }
        if (LogFormatUtils.isToolCall(typeLower, json)) {
            String toolCallId = LogFormatUtils.firstNonEmpty(json, "tool_call_id", "call_id", "id");
            String toolName = LogFormatUtils.firstNonEmpty(json, "tool_name", "toolName", "name");
            return ParsedLine.toolCall(lineNumber, toolName, text, rawDetails, toolCallId);
        }
        if (LogFormatUtils.isToolResult(typeLower, json)) {
            String toolCallId = LogFormatUtils.firstNonEmpty(json, "tool_call_id", "call_id", "id");
            String toolName = LogFormatUtils.firstNonEmpty(json, "tool_name", "toolName", "name");
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

    // --- JSON parsing ---

    private static JSONObject tryParseJson(String line) {
        if (!(line.startsWith("{") && line.endsWith("}"))) return null;
        try {
            return JSONObject.fromObject(line);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ---- Data classes ----

    public static final class ParsedLine {
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

        public static ParsedLine raw(long id, String line) {
            return new ParsedLine(id, "raw", "", line, "", "", "", line, null, false);
        }

        public static ParsedLine system(long id, String label, String content, String rawDetails) {
            return new ParsedLine(
                    id, "system", label, content, "", "", "", rawDetails, null, false);
        }

        public static ParsedLine message(
                long id, String category, String label, String content, String rawDetails) {
            return new ParsedLine(
                    id, category, label, content, "", "", "", rawDetails, null, false);
        }

        public static ParsedLine message(
                long id,
                String category,
                String label,
                String content,
                String rawDetails,
                boolean delta) {
            return new ParsedLine(
                    id, category, label, content, "", "", "", rawDetails, null, delta);
        }

        public static ParsedLine result(
                long id, String category, String label, String content, String rawDetails) {
            return new ParsedLine(
                    id, category, label, content, "", "", "", rawDetails, null, false);
        }

        public static ParsedLine thinking(long id, String content, String rawDetails) {
            return new ParsedLine(
                    id, "thinking", "Thinking", content, "", "", "", rawDetails, null, false);
        }

        public static ParsedLine toolCall(
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

        public static ParsedLine toolResult(
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
                return label + ": " + LogFormatUtils.excerpt(oneLine(content), 180);
            }
            if (!toolInput.isEmpty()) {
                return label + ": " + LogFormatUtils.excerpt(oneLine(toolInput), 180);
            }
            if (!toolOutput.isEmpty()) {
                return label + " result: " + LogFormatUtils.excerpt(oneLine(toolOutput), 180);
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

        private static String oneLine(String text) {
            if (text == null || text.isEmpty()) return "";
            return text.replaceAll("\\s+", " ").trim();
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
