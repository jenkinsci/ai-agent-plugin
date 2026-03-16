package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONObject;

/**
 * Strategy interface for extracting usage statistics from a single JSON log line emitted by an AI
 * agent.
 *
 * <p>Each {@link AiAgentTypeHandler} returns its own {@code AiAgentStatsExtractor} via {@link
 * AiAgentTypeHandler#getStatsExtractor()}, allowing third-party agent handlers to supply custom
 * stats extraction without modifying the core {@link AgentUsageStats} class.
 *
 * <p>Implementations should return {@code true} if the JSON line was handled (stats were
 * extracted), or {@code false} if the line was not recognised. When {@code false} is returned, the
 * shared extractor in {@link AgentUsageStats} is used as a fallback.
 */
public interface AiAgentStatsExtractor {

    /**
     * Attempt to extract usage statistics from a single JSON line.
     *
     * @param json the parsed JSON object for this line
     * @param stats the stats accumulator to update
     * @return {@code true} if this extractor handled the line, {@code false} to fall through to the
     *     shared extractor
     */
    boolean extract(JSONObject json, AgentUsageStats stats);
}
