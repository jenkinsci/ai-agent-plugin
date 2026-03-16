package io.jenkins.plugins.aiagentjob;

import net.sf.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Normalized token usage and cost statistics extracted from AI agent JSONL logs. Aggregates values
 * across all lines in the log (multiple turns, partial results, etc.) so the final object reflects
 * totals for the entire session.
 *
 * <p>Agent-specific extraction logic is delegated to {@link AiAgentStatsExtractor} implementations,
 * which are provided by each {@link AiAgentTypeHandler} via {@link
 * AiAgentTypeHandler#getStatsExtractor()}. Shared/common extraction (system init, result lines) is
 * handled by the base class as a fallback.
 */
public final class AgentUsageStats implements Serializable {
    private static final long serialVersionUID = 1L;

    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private long totalTokens;
    private long reasoningTokens;
    private double costUsd;
    private long durationMs;
    private long apiDurationMs;
    private int numTurns;
    private int toolCalls;
    private String detectedModel = "";

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public long getTotalTokens() {
        if (totalTokens > 0) return totalTokens;
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }

    public long getReasoningTokens() {
        return reasoningTokens;
    }

    public double getCostUsd() {
        return costUsd;
    }

    /** Formatted cost string like "$0.30" or empty if no cost data. */
    public String getCostDisplay() {
        if (costUsd <= 0) return "";
        return String.format(Locale.US, "$%.2f", costUsd);
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** Formatted duration like "4.5s" or "2m 15s". */
    public String getDurationDisplay() {
        if (durationMs <= 0) return "";
        if (durationMs < 1000) return durationMs + "ms";
        long secs = durationMs / 1000;
        if (secs < 60) return String.format(Locale.US, "%.1fs", durationMs / 1000.0);
        return (secs / 60) + "m " + (secs % 60) + "s";
    }

    public long getApiDurationMs() {
        return apiDurationMs;
    }

    public int getNumTurns() {
        return numTurns;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    /** Model name detected from system init or result lines, empty if not found. */
    public String getDetectedModel() {
        return detectedModel;
    }

    /** Formats a token count with comma grouping (e.g., "103,854"). */
    public String getInputTokensDisplay() {
        return formatNumber(inputTokens);
    }

    public String getOutputTokensDisplay() {
        return formatNumber(outputTokens);
    }

    public String getCacheReadTokensDisplay() {
        return formatNumber(cacheReadTokens);
    }

    public String getCacheWriteTokensDisplay() {
        return formatNumber(cacheWriteTokens);
    }

    public String getTotalTokensDisplay() {
        return formatNumber(getTotalTokens());
    }

    public String getReasoningTokensDisplay() {
        return formatNumber(reasoningTokens);
    }

    private static String formatNumber(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    /** Returns true if any meaningful data was extracted. */
    public boolean hasData() {
        return inputTokens > 0
                || outputTokens > 0
                || totalTokens > 0
                || costUsd > 0
                || durationMs > 0;
    }

    // --- Mutators used by AiAgentStatsExtractor implementations ---

    /** Accumulate input tokens (takes the max of current and new value). */
    public void addInputTokens(long value) {
        inputTokens = Math.max(inputTokens, value);
    }

    /** Accumulate output tokens (takes the max of current and new value). */
    public void addOutputTokens(long value) {
        outputTokens = Math.max(outputTokens, value);
    }

    /** Accumulate cache read tokens (takes the max of current and new value). */
    public void addCacheReadTokens(long value) {
        cacheReadTokens = Math.max(cacheReadTokens, value);
    }

    /** Accumulate cache write tokens (takes the max of current and new value). */
    public void addCacheWriteTokens(long value) {
        cacheWriteTokens = Math.max(cacheWriteTokens, value);
    }

    /** Accumulate total tokens (takes the max of current and new value). */
    public void addTotalTokens(long value) {
        totalTokens = Math.max(totalTokens, value);
    }

    /** Accumulate reasoning tokens (takes the max of current and new value). */
    public void addReasoningTokens(long value) {
        reasoningTokens = Math.max(reasoningTokens, value);
    }

    /** Increment input tokens (additive, for multi-step agents like OpenCode). */
    public void incrementInputTokens(long value) {
        inputTokens += value;
    }

    /** Increment output tokens (additive). */
    public void incrementOutputTokens(long value) {
        outputTokens += value;
    }

    /** Increment reasoning tokens (additive). */
    public void incrementReasoningTokens(long value) {
        reasoningTokens += value;
    }

    /** Increment total tokens (additive). */
    public void incrementTotalTokens(long value) {
        totalTokens += value;
    }

    /** Increment cache read tokens (additive). */
    public void incrementCacheReadTokens(long value) {
        cacheReadTokens += value;
    }

    /** Increment cache write tokens (additive). */
    public void incrementCacheWriteTokens(long value) {
        cacheWriteTokens += value;
    }

    /** Increment cost (additive). */
    public void incrementCostUsd(double value) {
        costUsd += value;
    }

    /** Set cost (takes the max of current and new value). */
    public void addCostUsd(double value) {
        costUsd = Math.max(costUsd, value);
    }

    /** Set duration (takes the max of current and new value). */
    public void addDurationMs(long value) {
        durationMs = Math.max(durationMs, value);
    }

    /** Set API duration (takes the max of current and new value). */
    public void addApiDurationMs(long value) {
        apiDurationMs = Math.max(apiDurationMs, value);
    }

    /** Set number of turns (takes the max of current and new value). */
    public void addNumTurns(int value) {
        numTurns = Math.max(numTurns, value);
    }

    /** Set tool call count (takes the max of current and new value). */
    public void addToolCalls(int value) {
        toolCalls = Math.max(toolCalls, value);
    }

    /** Set the detected model name if not already set. */
    public void setDetectedModelIfEmpty(String model) {
        if (model != null && !model.isEmpty() && detectedModel.isEmpty()) {
            detectedModel = model;
        }
    }

    /**
     * Parses the entire JSONL log file and returns aggregated stats. Each line that contains usage
     * or stats information contributes to the totals.
     */
    public static AgentUsageStats fromLogFile(File logFile) throws IOException {
        return fromLogFile(logFile, null);
    }

    /**
     * Parses the entire JSONL log file using the given extractor and returns aggregated stats. The
     * extractor is tried first for each line; if it returns {@code false}, the shared extractor
     * handles the line.
     */
    public static AgentUsageStats fromLogFile(File logFile, AiAgentStatsExtractor extractor)
            throws IOException {
        AgentUsageStats stats = new AgentUsageStats();
        if (logFile == null || !logFile.exists()) return stats;

        try (BufferedReader reader =
                Files.newBufferedReader(logFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("{") || !line.endsWith("}")) continue;
                try {
                    JSONObject json = JSONObject.fromObject(line);
                    stats.extractFrom(json, extractor);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return stats;
    }

    /** Extracts stats using the given extractor (if any), falling back to shared extraction. */
    public void extractFrom(JSONObject json, AiAgentStatsExtractor extractor) {
        // Always extract shared fields (model detection, common result structure)
        extractShared(json);

        // Try agent-specific extractor first
        if (extractor != null && extractor.extract(json, this)) {
            return;
        }

        // Fall back to shared extraction for common patterns
        extractSharedStats(json);
    }

    /** Extracts stats from a single JSON line without any agent-specific extractor. */
    public void extractFrom(JSONObject json) {
        extractFrom(json, null);
    }

    /**
     * Shared extraction: model detection from system/init events and result cost/duration. Always
     * runs regardless of whether the agent-specific extractor handled the line.
     */
    private void extractShared(JSONObject json) {
        String type = json.optString("type", "").toLowerCase(Locale.ROOT);

        if ("system".equals(type) || "init".equals(type)) {
            String model = json.optString("model", "").trim();
            setDetectedModelIfEmpty(model);
        }
    }

    /**
     * Shared stats extraction for common patterns: result events with usage, assistant message
     * usage blocks, and common usage structures. Acts as a fallback when no agent-specific
     * extractor is provided or when the extractor doesn't handle the line.
     */
    private void extractSharedStats(JSONObject json) {
        String type = json.optString("type", "").toLowerCase(Locale.ROOT);

        if ("result".equals(type)) {
            extractResultStats(json);
        }

        if ("assistant".equals(type)) {
            JSONObject message = json.optJSONObject("message");
            if (message != null) {
                JSONObject usage = message.optJSONObject("usage");
                if (usage != null) {
                    accumulateUsage(usage);
                }
            }
        }
    }

    /** Extracts stats from a "result" event (Claude Code / Gemini / Cursor shared structure). */
    public void extractResultStats(JSONObject json) {
        costUsd = Math.max(costUsd, json.optDouble("total_cost_usd", 0));
        durationMs = Math.max(durationMs, json.optLong("duration_ms", 0));
        apiDurationMs = Math.max(apiDurationMs, json.optLong("duration_api_ms", 0));
        numTurns = Math.max(numTurns, json.optInt("num_turns", 0));

        JSONObject usage = json.optJSONObject("usage");
        if (usage != null) {
            accumulateUsage(usage);
        }

        // Gemini stores stats differently
        JSONObject statsBlock = json.optJSONObject("stats");
        if (statsBlock != null) {
            extractGeminiStats(statsBlock);
        }
    }

    /** Accumulates token counts from a standard usage object. */
    public void accumulateUsage(JSONObject usage) {
        inputTokens =
                Math.max(
                        inputTokens,
                        usage.optLong("input_tokens", usage.optLong("inputTokens", 0)));
        outputTokens =
                Math.max(
                        outputTokens,
                        usage.optLong("output_tokens", usage.optLong("outputTokens", 0)));
        cacheReadTokens =
                Math.max(
                        cacheReadTokens,
                        usage.optLong(
                                "cache_read_input_tokens",
                                usage.optLong(
                                        "cached_input_tokens",
                                        usage.optLong("cacheReadTokens", 0))));
        cacheWriteTokens =
                Math.max(
                        cacheWriteTokens,
                        usage.optLong(
                                "cache_creation_input_tokens",
                                usage.optLong(
                                        "cacheWriteTokens",
                                        usage.optLong("cacheCreationInputTokens", 0))));

        // Codex uses cached_input_tokens
        if (usage.has("cached_input_tokens") && !usage.has("cache_read_input_tokens")) {
            cacheReadTokens = Math.max(cacheReadTokens, usage.optLong("cached_input_tokens", 0));
        }
    }

    private void extractGeminiStats(JSONObject stats) {
        totalTokens = Math.max(totalTokens, stats.optLong("total_tokens", 0));
        inputTokens =
                Math.max(inputTokens, stats.optLong("input_tokens", stats.optLong("input", 0)));
        outputTokens =
                Math.max(outputTokens, stats.optLong("output_tokens", stats.optLong("output", 0)));
        cacheReadTokens = Math.max(cacheReadTokens, stats.optLong("cached", 0));
        durationMs = Math.max(durationMs, stats.optLong("duration_ms", 0));
        toolCalls = Math.max(toolCalls, stats.optInt("tool_calls", 0));
    }
}
