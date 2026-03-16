package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONObject;

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
}
