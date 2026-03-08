package io.jenkins.plugins.aiagentjob;

import hudson.model.Run;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-memory registry of live AI agent builds, used to coordinate tool-call approval gates between
 * the running agent process and the Jenkins web UI.
 */
public final class ExecutionRegistry {
    private static final Map<String, LiveExecution> LIVE_RUNS = new ConcurrentHashMap<>();

    private ExecutionRegistry() {}

    public static LiveExecution register(Run<?, ?> run, int invocationId) {
        LiveExecution liveExecution = new LiveExecution();
        LIVE_RUNS.put(key(run, invocationId), liveExecution);
        return liveExecution;
    }

    public static LiveExecution get(Run<?, ?> run, int invocationId) {
        return LIVE_RUNS.get(key(run, invocationId));
    }

    public static void unregister(Run<?, ?> run, int invocationId) {
        LIVE_RUNS.remove(key(run, invocationId));
    }

    private static String key(Run<?, ?> run, int invocationId) {
        return run.getExternalizableId() + "#" + invocationId;
    }

    /** Live mutable state for one running build. */
    public static final class LiveExecution {
        private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<ApprovalDecision>> decisions =
                new ConcurrentHashMap<>();

        PendingApproval createPendingApproval(
                String toolCallId, String toolName, String inputSummary) {
            String id = UUID.randomUUID().toString();
            PendingApproval pending =
                    new PendingApproval(id, toolCallId, toolName, inputSummary, Instant.now());
            pendingApprovals.put(id, pending);
            decisions.put(id, new CompletableFuture<>());
            return pending;
        }

        ApprovalDecision awaitDecision(PendingApproval pendingApproval, Duration timeout) {
            CompletableFuture<ApprovalDecision> future = decisions.get(pendingApproval.getId());
            if (future == null) {
                return ApprovalDecision.denied("approval request disappeared");
            }
            try {
                ApprovalDecision decision = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                pendingApprovals.remove(pendingApproval.getId());
                decisions.remove(pendingApproval.getId());
                return decision;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalDecision.denied("interrupted while waiting for approval");
            } catch (ExecutionException e) {
                return ApprovalDecision.denied("approval failed: " + e.getMessage());
            } catch (TimeoutException e) {
                pendingApprovals.remove(pendingApproval.getId());
                decisions.remove(pendingApproval.getId());
                return ApprovalDecision.denied(
                        "approval timed out after " + timeout.toSeconds() + "s");
            }
        }

        public List<PendingApproval> getPendingApprovals() {
            List<PendingApproval> list = new ArrayList<>(pendingApprovals.values());
            list.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
            return Collections.unmodifiableList(list);
        }

        public boolean approve(String id) {
            CompletableFuture<ApprovalDecision> future = decisions.get(id);
            if (future == null) {
                return false;
            }
            boolean completed = future.complete(ApprovalDecision.approved());
            if (completed) {
                pendingApprovals.remove(id);
                decisions.remove(id);
            }
            return completed;
        }

        public boolean deny(String id, String reason) {
            CompletableFuture<ApprovalDecision> future = decisions.get(id);
            if (future == null) {
                return false;
            }
            boolean completed = future.complete(ApprovalDecision.denied(reason));
            if (completed) {
                pendingApprovals.remove(id);
                decisions.remove(id);
            }
            return completed;
        }
    }

    /** Immutable view model for one outstanding approval request. */
    public static final class PendingApproval {
        private final String id;
        private final String toolCallId;
        private final String toolName;
        private final String inputSummary;
        private final Instant createdAt;

        PendingApproval(
                String id,
                String toolCallId,
                String toolName,
                String inputSummary,
                Instant createdAt) {
            this.id = id;
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.inputSummary = inputSummary;
            this.createdAt = createdAt;
        }

        public String getId() {
            return id;
        }

        public String getToolCallId() {
            return toolCallId;
        }

        public String getToolName() {
            return toolName;
        }

        public String getInputSummary() {
            return inputSummary;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }
    }

    /** Result of a user approval decision. */
    public static final class ApprovalDecision {
        private final boolean approved;
        private final String reason;

        private ApprovalDecision(boolean approved, String reason) {
            this.approved = approved;
            this.reason = reason;
        }

        public static ApprovalDecision approved() {
            return new ApprovalDecision(true, null);
        }

        public static ApprovalDecision denied(String reason) {
            String safeReason =
                    (reason == null || reason.trim().isEmpty()) ? "denied by user" : reason.trim();
            return new ApprovalDecision(false, safeReason);
        }

        public boolean isApproved() {
            return approved;
        }

        public String getReason() {
            return reason;
        }
    }
}
