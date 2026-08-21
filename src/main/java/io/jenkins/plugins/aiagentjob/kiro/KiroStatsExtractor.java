package io.jenkins.plugins.aiagentjob.kiro;

import io.jenkins.plugins.aiagentjob.AgentUsageStats;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.LogFormatUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/** Extracts model, token, duration, turn, and tool statistics from Kiro CLI JSONL. */
public final class KiroStatsExtractor implements AiAgentStatsExtractor {
    public static final KiroStatsExtractor INSTANCE = new KiroStatsExtractor();

    private KiroStatsExtractor() {}

    @Override
    public boolean extract(JSONObject json, AgentUsageStats stats) {
        String kind = LogFormatUtils.normalize(json.optString("kind", ""));
        if (!kind.isEmpty()) {
            return extractSessionJsonl(json, stats);
        }
        String type = LogFormatUtils.normalize(json.optString("type", ""));
        if (!type.isEmpty()) {
            return extractTypedJson(json, stats);
        }
        String method = json.optString("method", "");
        if ("session/update".equals(method)) {
            return extractAcpUpdate(json, stats);
        }
        return false;
    }

    private boolean extractSessionJsonl(JSONObject json, AgentUsageStats stats) {
        String kind = LogFormatUtils.normalize(json.optString("kind", ""));
        if ("prompt".equals(kind)) {
            return false;
        }
        if ("assistantmessage".equals(kind)) {
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                stats.incrementNumTurns(1);
                JSONObject usage = data.optJSONObject("usage");
                if (usage != null) {
                    accumulateUsage(usage, stats);
                }
            }
            return true;
        }
        if ("toolresults".equals(kind)) {
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                JSONArray content = data.optJSONArray("content");
                if (content != null) {
                    for (int i = 0; i < content.size(); i++) {
                        Object item = content.get(i);
                        if (item instanceof JSONObject) {
                            JSONObject block = (JSONObject) item;
                            if ("toolresult"
                                    .equals(
                                            LogFormatUtils.normalize(
                                                    block.optString("kind", "")))) {
                                JSONObject resultData = block.optJSONObject("data");
                                if (resultData != null) {
                                    stats.recordToolCall(resultData.optString("toolUseId", ""));
                                }
                            }
                        }
                    }
                }
                JSONObject results = data.optJSONObject("results");
                if (results != null) {
                    for (Object key : results.keySet()) {
                        JSONObject result = results.optJSONObject(String.valueOf(key));
                        if (result == null) continue;
                        JSONObject usage = result.optJSONObject("usage");
                        if (usage != null) {
                            accumulateUsage(usage, stats);
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    private boolean extractTypedJson(JSONObject json, AgentUsageStats stats) {
        String type = LogFormatUtils.normalize(json.optString("type", ""));
        if ("init".equals(type)) {
            stats.setDetectedModelIfEmpty(LogFormatUtils.firstNonEmpty(json, "model"));
            return true;
        }
        if ("result".equals(type)) {
            JSONObject usage = json.optJSONObject("usage");
            if (usage != null) {
                accumulateUsage(usage, stats);
            }
            stats.addDurationMs(json.optLong("duration_ms", 0));
            stats.addNumTurns(json.optInt("num_turns", 0));
            return true;
        }
        return false;
    }

    private boolean extractAcpUpdate(JSONObject json, AgentUsageStats stats) {
        JSONObject params = json.optJSONObject("params");
        JSONObject update = params == null ? null : params.optJSONObject("update");
        if (update == null) {
            return false;
        }
        String updateType = LogFormatUtils.normalize(update.optString("sessionUpdate", ""));
        if ("agent_message_chunk".equals(updateType)) {
            stats.addNumTurns(1);
            return true;
        }
        if ("tool_call".equals(updateType)) {
            stats.recordToolCall(LogFormatUtils.firstNonEmpty(update, "toolCallId"));
            return true;
        }
        if ("usage_update".equals(updateType)) {
            JSONObject usage = update.optJSONObject("usage");
            if (usage != null) {
                accumulateUsage(usage, stats);
            }
            return true;
        }
        return false;
    }

    private static void accumulateUsage(JSONObject usage, AgentUsageStats stats) {
        stats.incrementInputTokens(usage.optLong("input_tokens", 0));
        stats.incrementOutputTokens(usage.optLong("output_tokens", 0));
        stats.incrementReasoningTokens(usage.optLong("reasoning_tokens", 0));
        stats.incrementCacheReadTokens(usage.optLong("cache_read_tokens", 0));
        stats.incrementTotalTokens(usage.optLong("total_tokens", 0));
    }
}
