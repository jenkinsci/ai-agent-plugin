package io.jenkins.plugins.aiagentjob;

import hudson.model.Action;
import hudson.model.Item;
import hudson.model.Run;
import hudson.security.csrf.CrumbIssuer;
import hudson.util.HttpResponses;

import jenkins.model.Jenkins;
import jenkins.model.RunAction2;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Per-build action that stores AI agent invocation metadata and provides the inline conversation
 * view on the build page. Supports multiple invocations in a single build.
 */
public class AiAgentRunAction implements Action, RunAction2 {
    private static final String RAW_LOG_FILE_PREFIX = "ai-agent-stream-";
    private static final String RAW_LOG_FILE_SUFFIX = ".jsonl";

    private transient Run<?, ?> run;
    private List<InvocationRecord> invocations = new ArrayList<>();
    private int nextInvocationId = 1;

    public static AiAgentRunAction getOrCreate(Run<?, ?> run) {
        AiAgentRunAction existing = run.getAction(AiAgentRunAction.class);
        if (existing != null) {
            return existing;
        }
        AiAgentRunAction created = new AiAgentRunAction();
        run.addAction(created);
        created.onAttached(run);
        return created;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return "AI Agent Conversation";
    }

    @Override
    public String getUrlName() {
        return "ai-agent";
    }

    @Override
    public void onAttached(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onLoad(Run<?, ?> run) {
        this.run = run;
    }

    public synchronized int markStarted(
            String agentTypeDisplayName,
            String prompt,
            String model,
            String commandLine,
            boolean yoloMode,
            boolean approvalsEnabled)
            throws IOException {
        int id = nextInvocationId++;
        InvocationRecord invocation =
                new InvocationRecord(
                        id,
                        agentTypeDisplayName == null ? "" : agentTypeDisplayName,
                        prompt == null ? "" : prompt,
                        model == null ? "" : model,
                        commandLine == null ? "" : commandLine,
                        yoloMode,
                        approvalsEnabled,
                        System.currentTimeMillis());
        invocations.add(invocation);
        run.save();
        return id;
    }

    public synchronized void markCompleted(int invocationId, int exitCode) throws IOException {
        InvocationRecord invocation = getInvocation(invocationId);
        if (invocation == null) {
            return;
        }
        invocation.exitCode = exitCode;
        invocation.completedAtMillis = System.currentTimeMillis();
        run.save();
    }

    public synchronized List<InvocationRecord> getInvocations() {
        return Collections.unmodifiableList(new ArrayList<>(invocations));
    }

    public synchronized List<InvocationRecord> getInvocationsNewestFirst() {
        List<InvocationRecord> copy = new ArrayList<>(invocations);
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    public synchronized boolean hasInvocations() {
        return !invocations.isEmpty();
    }

    public synchronized int getLatestInvocationId() {
        InvocationRecord latest = latestInvocation();
        return latest == null ? 0 : latest.id;
    }

    public synchronized String getAgentType() {
        InvocationRecord latest = latestInvocation();
        return latest == null ? "" : latest.agentType;
    }

    public synchronized String getPrompt() {
        InvocationRecord latest = latestInvocation();
        return latest == null || latest.prompt == null ? "" : latest.prompt;
    }

    public synchronized String getModel() {
        InvocationRecord latest = latestInvocation();
        if (latest == null) {
            return "";
        }
        if (latest.model != null && !latest.model.isEmpty()) {
            return latest.model;
        }
        try {
            String detected =
                    AgentUsageStats.fromLogFile(getRawLogFile(latest.id)).getDetectedModel();
            if (!detected.isEmpty()) {
                return detected;
            }
        } catch (IOException ignored) {
        }
        return latest.model;
    }

    public synchronized String getCommandLine() {
        InvocationRecord latest = latestInvocation();
        return latest == null ? "" : latest.commandLine;
    }

    public synchronized boolean isYoloMode() {
        InvocationRecord latest = latestInvocation();
        return latest != null && latest.yoloMode;
    }

    public synchronized boolean isApprovalsEnabled() {
        InvocationRecord latest = latestInvocation();
        return latest != null && latest.approvalsEnabled;
    }

    public synchronized String getStartedAt() {
        InvocationRecord latest = latestInvocation();
        if (latest == null || latest.startedAtMillis <= 0L) {
            return "";
        }
        return new Date(latest.startedAtMillis).toString();
    }

    public synchronized String getCompletedAt() {
        InvocationRecord latest = latestInvocation();
        if (latest == null || latest.completedAtMillis <= 0L) {
            return null;
        }
        return new Date(latest.completedAtMillis).toString();
    }

    public synchronized Integer getExitCode() {
        InvocationRecord latest = latestInvocation();
        return latest == null ? null : latest.exitCode;
    }

    public synchronized boolean isLive() {
        InvocationRecord latest = latestInvocation();
        return latest != null && run != null && run.isBuilding() && latest.exitCode == null;
    }

    public List<ExecutionRegistry.PendingApproval> getPendingApprovals() {
        int invocationId = resolveRequestedInvocationId();
        return getPendingApprovals(invocationId);
    }

    public List<ExecutionRegistry.PendingApproval> getPendingApprovals(int invocationId) {
        if (run == null || invocationId <= 0) {
            return Collections.emptyList();
        }
        ExecutionRegistry.LiveExecution liveExecution = ExecutionRegistry.get(run, invocationId);
        if (liveExecution == null) {
            return Collections.emptyList();
        }
        return liveExecution.getPendingApprovals();
    }

    public List<AiAgentLogParser.EventView> getEvents() {
        int invocationId = resolveRequestedInvocationId();
        return getEvents(invocationId);
    }

    public List<AiAgentLogParser.EventView> getEvents(int invocationId) {
        File raw = getRawLogFile(invocationId);
        if (raw == null || !raw.exists()) {
            return Collections.emptyList();
        }
        try {
            return AiAgentLogParser.parse(raw);
        } catch (IOException e) {
            return Collections.singletonList(
                    new AiAgentLogParser.EventView(
                            -1,
                            "error",
                            "Error",
                            "Failed to read raw agent logs: " + e,
                            "",
                            "",
                            "",
                            Instant.now(),
                            false));
        }
    }

    public AgentUsageStats getUsageStats() {
        int invocationId = resolveRequestedInvocationId();
        return getUsageStats(invocationId);
    }

    public AgentUsageStats getUsageStats(int invocationId) {
        File raw = getRawLogFile(invocationId);
        if (raw == null || !raw.exists()) {
            return new AgentUsageStats();
        }
        try {
            return AgentUsageStats.fromLogFile(raw);
        } catch (IOException e) {
            return new AgentUsageStats();
        }
    }

    public File getRawLogFile() {
        int invocationId = resolveRequestedInvocationId();
        return getRawLogFile(invocationId);
    }

    public File getRawLogFile(int invocationId) {
        if (run == null || invocationId <= 0) {
            return run == null
                    ? null
                    : new File(
                            run.getRootDir(), RAW_LOG_FILE_PREFIX + "latest" + RAW_LOG_FILE_SUFFIX);
        }
        return new File(run.getRootDir(), RAW_LOG_FILE_PREFIX + invocationId + RAW_LOG_FILE_SUFFIX);
    }

    public synchronized boolean isInvocationLive(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        return invocation != null
                && run != null
                && run.isBuilding()
                && invocation.exitCode == null
                && invocation.id == getLatestInvocationId();
    }

    public synchronized Integer getInvocationExitCode(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        return invocation == null ? null : invocation.exitCode;
    }

    public synchronized boolean isLatestInvocation(int invocationId) {
        InvocationRecord latest = latestInvocation();
        return latest != null && latest.id == invocationId;
    }

    public synchronized String getInvocationModel(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        if (invocation == null) {
            return "";
        }
        if (invocation.model != null && !invocation.model.isEmpty()) {
            return invocation.model;
        }
        try {
            String detected =
                    AgentUsageStats.fromLogFile(getRawLogFile(invocationId)).getDetectedModel();
            if (!detected.isEmpty()) {
                return detected;
            }
        } catch (IOException ignored) {
        }
        return invocation.model;
    }

    public synchronized String getInvocationPrompt(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        return invocation == null || invocation.prompt == null ? "" : invocation.prompt;
    }

    public synchronized String getInvocationAgentType(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        return invocation == null ? "" : invocation.agentType;
    }

    public synchronized String getInvocationCommandLine(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        return invocation == null ? "" : invocation.commandLine;
    }

    public synchronized String getInvocationStartedAt(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        if (invocation == null || invocation.startedAtMillis <= 0L) {
            return "";
        }
        return new Date(invocation.startedAtMillis).toString();
    }

    public synchronized String getInvocationCompletedAt(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        if (invocation == null || invocation.completedAtMillis <= 0L) {
            return "";
        }
        return new Date(invocation.completedAtMillis).toString();
    }

    public synchronized boolean getInvocationYoloMode(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        return invocation != null && invocation.yoloMode;
    }

    public synchronized boolean getInvocationApprovalsEnabled(int invocationId) {
        InvocationRecord invocation = getInvocation(invocationId);
        return invocation != null && invocation.approvalsEnabled;
    }

    public int getSelectedInvocationId() {
        return resolveRequestedInvocationId();
    }

    @RequirePOST
    public Object doApprove(@QueryParameter String id) {
        checkBuildPermission();
        if (id == null || id.trim().isEmpty()) {
            return HttpResponses.errorWithoutStack(400, "Missing approval id");
        }
        int invocationId = resolveRequestedInvocationId();
        ExecutionRegistry.LiveExecution liveExecution = ExecutionRegistry.get(run, invocationId);
        if (liveExecution == null || !liveExecution.approve(id)) {
            return HttpResponses.errorWithoutStack(404, "Approval request not found");
        }
        return HttpResponses.redirectToDot();
    }

    @RequirePOST
    public Object doDeny(@QueryParameter String id, @QueryParameter String reason) {
        checkBuildPermission();
        if (id == null || id.trim().isEmpty()) {
            return HttpResponses.errorWithoutStack(400, "Missing approval id");
        }
        int invocationId = resolveRequestedInvocationId();
        ExecutionRegistry.LiveExecution liveExecution = ExecutionRegistry.get(run, invocationId);
        if (liveExecution == null || !liveExecution.deny(id, reason)) {
            return HttpResponses.errorWithoutStack(404, "Approval request not found");
        }
        return HttpResponses.redirectToDot();
    }

    @GET
    public void doProgressiveEvents(
            org.kohsuke.stapler.StaplerRequest2 request,
            org.kohsuke.stapler.StaplerResponse2 response)
            throws IOException {
        checkReadPermission();
        long startLine = 0;
        String startParam = request.getParameter("start");
        if (startParam != null) {
            try {
                startLine = Long.parseLong(startParam);
            } catch (NumberFormatException ignored) {
            }
        }

        int invocationId = parseInvocationId(request.getParameter("invocation"));
        if (invocationId <= 0) {
            invocationId = getLatestInvocationId();
        }

        File raw = getRawLogFile(invocationId);
        List<AiAgentLogParser.EventView> newEvents = new ArrayList<>();
        long lineCount = 0;

        if (raw != null && raw.exists()) {
            try (BufferedReader reader =
                    Files.newBufferedReader(raw.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    if (lineCount <= startLine) {
                        continue;
                    }
                    AiAgentLogParser.EventView ev =
                            AiAgentLogParser.parseLine(lineCount, line).toEventView();
                    if (!ev.isEmpty()) {
                        newEvents.add(ev);
                    }
                }
            }
        }

        JSONArray eventsJson = new JSONArray();
        for (AiAgentLogParser.EventView ev : newEvents) {
            JSONObject obj = new JSONObject();
            obj.put("id", ev.getId());
            obj.put("category", ev.getCategory());
            obj.put("categoryLabel", ev.getCategoryLabel());
            obj.put("label", ev.getLabel());
            obj.put("content", ev.getContent());
            obj.put("toolInput", ev.getToolInput());
            obj.put("toolOutput", ev.getToolOutput());
            obj.put("summary", ev.getSummary());
            obj.put("delta", ev.isDelta());
            eventsJson.add(obj);
        }

        JSONObject result = new JSONObject();
        result.put("events", eventsJson);
        result.put("nextStart", lineCount);
        result.put("live", isInvocationLive(invocationId));
        result.put("exitCode", getInvocationExitCode(invocationId));
        result.put("invocation", invocationId);

        JSONArray approvalsJson = new JSONArray();
        for (ExecutionRegistry.PendingApproval pa : getPendingApprovals(invocationId)) {
            JSONObject paObj = new JSONObject();
            paObj.put("id", pa.getId());
            paObj.put("toolName", pa.getToolName());
            paObj.put("toolCallId", pa.getToolCallId());
            paObj.put("inputSummary", pa.getInputSummary());
            approvalsJson.add(paObj);
        }
        result.put("pendingApprovals", approvalsJson);
        addCrumb(result, request);

        if (!isInvocationLive(invocationId)) {
            AgentUsageStats stats = getUsageStats(invocationId);
            if (stats.hasData()) {
                JSONObject statsJson = new JSONObject();
                statsJson.put("inputTokens", stats.getInputTokens());
                statsJson.put("outputTokens", stats.getOutputTokens());
                statsJson.put("cacheReadTokens", stats.getCacheReadTokens());
                statsJson.put("cacheWriteTokens", stats.getCacheWriteTokens());
                statsJson.put("totalTokens", stats.getTotalTokens());
                statsJson.put("reasoningTokens", stats.getReasoningTokens());
                statsJson.put("costDisplay", stats.getCostDisplay());
                statsJson.put("durationDisplay", stats.getDurationDisplay());
                statsJson.put("numTurns", stats.getNumTurns());
                statsJson.put("toolCalls", stats.getToolCalls());
                result.put("usageStats", statsJson);
            }
        }

        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(result.toString());
    }

    @GET
    public Object doIndex() {
        checkReadPermission();
        int invocationId = getLatestInvocationId();
        if (invocationId <= 0) {
            return HttpResponses.redirectTo("../");
        }
        return HttpResponses.redirectTo("conversation?invocation=" + invocationId);
    }

    @GET
    public Object doConversation() {
        checkReadPermission();
        return org.kohsuke.stapler.HttpResponses.forwardToView(this, "conversation.jelly");
    }

    public String getRawContent() {
        return getRawContent(resolveRequestedInvocationId());
    }

    public String getRawContent(int invocationId) {
        checkReadPermission();
        File raw = getRawLogFile(invocationId);
        if (raw == null || !raw.exists()) {
            return "No raw log file has been captured yet.";
        }
        try {
            return Files.readString(raw.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Failed to read raw log file: " + e;
        }
    }

    private synchronized InvocationRecord latestInvocation() {
        return invocations.isEmpty() ? null : invocations.get(invocations.size() - 1);
    }

    private synchronized InvocationRecord getInvocation(int invocationId) {
        for (InvocationRecord invocation : invocations) {
            if (invocation.id == invocationId) {
                return invocation;
            }
        }
        return null;
    }

    private int resolveRequestedInvocationId() {
        org.kohsuke.stapler.StaplerRequest2 request = Stapler.getCurrentRequest2();
        if (request != null) {
            int requested = parseInvocationId(request.getParameter("invocation"));
            if (requested > 0) {
                return requested;
            }
        }
        return getLatestInvocationId();
    }

    private static int parseInvocationId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void addCrumb(JSONObject result, org.kohsuke.stapler.StaplerRequest2 request) {
        CrumbIssuer issuer = Jenkins.get().getCrumbIssuer();
        if (issuer == null) {
            return;
        }
        result.put("crumbRequestField", issuer.getCrumbRequestField());
        result.put("crumb", issuer.getCrumb(request));
    }

    private void checkReadPermission() {
        run.getParent().checkPermission(Item.READ);
    }

    private void checkBuildPermission() {
        run.getParent().checkPermission(Item.BUILD);
    }

    /** Serialized metadata for one invocation in a run. */
    public static final class InvocationRecord implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int id;
        private final String agentType;
        private final String prompt;
        private final String model;
        private final String commandLine;
        private final boolean yoloMode;
        private final boolean approvalsEnabled;
        private final long startedAtMillis;

        private long completedAtMillis;
        private Integer exitCode;

        InvocationRecord(
                int id,
                String agentType,
                String prompt,
                String model,
                String commandLine,
                boolean yoloMode,
                boolean approvalsEnabled,
                long startedAtMillis) {
            this.id = id;
            this.agentType = agentType;
            this.prompt = prompt;
            this.model = model;
            this.commandLine = commandLine;
            this.yoloMode = yoloMode;
            this.approvalsEnabled = approvalsEnabled;
            this.startedAtMillis = startedAtMillis;
        }

        public int getId() {
            return id;
        }

        public String getAgentType() {
            return agentType;
        }

        public String getPrompt() {
            return prompt;
        }

        public String getModel() {
            return model;
        }

        public String getCommandLine() {
            return commandLine;
        }

        public boolean isYoloMode() {
            return yoloMode;
        }

        public boolean isApprovalsEnabled() {
            return approvalsEnabled;
        }

        public Integer getExitCode() {
            return exitCode;
        }

        public String getStartedAt() {
            return startedAtMillis <= 0L ? "" : new Date(startedAtMillis).toString();
        }

        public String getCompletedAt() {
            return completedAtMillis <= 0L ? "" : new Date(completedAtMillis).toString();
        }
    }
}
