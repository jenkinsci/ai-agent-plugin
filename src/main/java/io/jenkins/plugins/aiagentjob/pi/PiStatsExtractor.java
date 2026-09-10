package io.jenkins.plugins.aiagentjob.pi;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public final class PiStatsExtractor implements AiAgentStatsExtractor {
    public static final PiStatsExtractor INSTANCE = new PiStatsExtractor();

    private PiStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        String type = json.optString("type");
        if ("tool_execution_start".equals(type) || "tool_execution_end".equals(type)) {
            stats.recordToolCall(json.optString("toolCallId"));
            return true;
        }
        // Usage snapshots also appear in updates, turn_end, and agent_end. Count only message_end.
        if (!"message_end".equals(type)) return PiLogFormat.isUsageSnapshot(type);
        JSONObject message = json.optJSONObject("message");
        if (message == null) return true;
        String role = message.optString("role");
        if ("assistant".equals(role)) {
            stats.setDetectedModelIfEmpty(message.optString("model"));
            stats.addNumTurns(stats.getNumTurns() + 1);
            JSONArray content = message.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.size(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block != null && "toolCall".equals(block.optString("type"))) {
                        stats.recordToolCall(block.optString("id"));
                    }
                }
            }
        } else if ("toolResult".equals(role)) {
            stats.recordToolCall(message.optString("toolCallId"));
        } else {
            return true;
        }
        JSONObject usage = message.optJSONObject("usage");
        if (usage != null) {
            stats.incrementInputTokens(usage.optLong("input", 0));
            stats.incrementOutputTokens(usage.optLong("output", 0));
            stats.incrementCacheReadTokens(usage.optLong("cacheRead", 0));
            stats.incrementCacheWriteTokens(usage.optLong("cacheWrite", 0));
            stats.incrementReasoningTokens(usage.optLong("reasoning", 0));
            stats.incrementTotalTokens(usage.optLong("totalTokens", 0));
            JSONObject cost = usage.optJSONObject("cost");
            if (cost != null) {
                double total = cost.optDouble("total", 0);
                if (Double.isFinite(total) && total > 0) stats.incrementCostUsd(total);
            }
        }
        return true;
    }
}
