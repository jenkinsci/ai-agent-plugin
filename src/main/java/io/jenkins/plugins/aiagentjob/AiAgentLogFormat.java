package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONObject;

import java.util.List;

/**
 * Strategy interface for classifying a JSON log line emitted by an AI agent into a {@link
 * AiAgentLogParser.ParsedLine}.
 *
 * <p>Each {@link AiAgentTypeHandler} returns its own {@code AiAgentLogFormat} via {@link
 * AiAgentTypeHandler#getLogFormat()}, allowing third-party agent handlers to supply custom log
 * parsing without modifying the core parser.
 *
 * <p>Implementations should return {@code null} for any JSON structure they do not recognise; the
 * parser will then fall through to the shared format and generic fallback.
 */
public interface AiAgentLogFormat {

    /**
     * Attempt to classify a single JSON object into a parsed event.
     *
     * @param lineNumber 1-based line number in the raw log file
     * @param json the parsed JSON object for this line
     * @return a classified {@link AiAgentLogParser.ParsedLine}, or {@code null} if this format does
     *     not handle the given JSON structure
     */
    AiAgentLogParser.ParsedLine classify(long lineNumber, JSONObject json);

    /**
     * Attempt to classify a JSON object that may contain multiple displayable events.
     *
     * <p>Implementations that emit at most one event can rely on this default. Returning {@code
     * null} still means the format did not recognize the input; an empty list means it recognized
     * the input but intentionally produced no visible events.
     */
    default List<AiAgentLogParser.ParsedLine> classifyAll(long lineNumber, JSONObject json) {
        AiAgentLogParser.ParsedLine parsed = classify(lineNumber, json);
        return parsed == null ? null : List.of(parsed);
    }

    /**
     * Attempt to classify a raw (non-JSON) log line.
     *
     * <p>The default implementation returns a raw parsed line. Agents that emit plain-text output
     * can override this to produce assistant messages or other structured events from text lines.
     *
     * @param lineNumber 1-based line number in the raw log file
     * @param line the trimmed raw log line
     * @return a classified {@link AiAgentLogParser.ParsedLine}, or {@code null} to fall back to
     *     {@link AiAgentLogParser.ParsedLine#raw(long, String)}
     */
    default List<AiAgentLogParser.ParsedLine> classifyRaw(long lineNumber, String line) {
        return List.of(AiAgentLogParser.ParsedLine.raw(lineNumber, line));
    }
}
