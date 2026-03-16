package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class ExecutionRegistryTest {

    @Test
    void pendingApproval_createdWithCorrectFields() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "ls -la");

        assertEquals("tc-1", pending.getToolCallId());
        assertEquals("bash", pending.getToolName());
        assertEquals("ls -la", pending.getInputSummary());
        assertNotNull(pending.getId());
        assertNotNull(pending.getCreatedAt());
    }

    @Test
    void pendingApprovals_listedCorrectly() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        live.createPendingApproval("tc-1", "bash", "ls");
        live.createPendingApproval("tc-2", "read", "file.txt");

        List<ExecutionRegistry.PendingApproval> approvals = live.getPendingApprovals();
        assertEquals(2, approvals.size());
    }

    @Test
    void approve_resolvesDecision() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "ls");

        // Approve in another thread
        new Thread(
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            live.approve(pending.getId());
                        })
                .start();

        ExecutionRegistry.ApprovalDecision decision =
                live.awaitDecision(pending, Duration.ofSeconds(5));
        assertTrue(decision.isApproved(), "Should be approved");
    }

    @Test
    void deny_resolvesDecision() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "rm -rf /");

        new Thread(
                        () -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            live.deny(pending.getId(), "dangerous command");
                        })
                .start();

        ExecutionRegistry.ApprovalDecision decision =
                live.awaitDecision(pending, Duration.ofSeconds(5));
        assertFalse(decision.isApproved(), "Should be denied");
        assertEquals("dangerous command", decision.getReason());
    }

    @Test
    void timeout_deniesDecision() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        ExecutionRegistry.PendingApproval pending =
                live.createPendingApproval("tc-1", "bash", "ls");

        ExecutionRegistry.ApprovalDecision decision =
                live.awaitDecision(pending, Duration.ofMillis(100));
        assertFalse(decision.isApproved(), "Should be denied on timeout");
        assertTrue(
                decision.getReason().toLowerCase().contains("timed out")
                        || decision.getReason().toLowerCase().contains("timeout"),
                "Reason should mention timed out");
    }

    @Test
    void approvalDecision_factoryMethods() {
        ExecutionRegistry.ApprovalDecision approved = ExecutionRegistry.ApprovalDecision.approved();
        assertTrue(approved.isApproved());

        ExecutionRegistry.ApprovalDecision denied =
                ExecutionRegistry.ApprovalDecision.denied("reason");
        assertFalse(denied.isApproved());
        assertEquals("reason", denied.getReason());
    }

    @Test
    void approve_returnsFalseForUnknownId() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        assertFalse(live.approve("nonexistent-id"));
    }

    @Test
    void deny_returnsFalseForUnknownId() {
        ExecutionRegistry.LiveExecution live = new ExecutionRegistry.LiveExecution();
        assertFalse(live.deny("nonexistent-id", "reason"));
    }
}
