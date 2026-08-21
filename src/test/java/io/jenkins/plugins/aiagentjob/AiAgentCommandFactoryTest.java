package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.aiagentjob.antigravity.AntigravityAgentHandler;
import io.jenkins.plugins.aiagentjob.claudecode.ClaudeCodeAgentHandler;
import io.jenkins.plugins.aiagentjob.codex.CodexAgentHandler;
import io.jenkins.plugins.aiagentjob.cursor.CursorAgentHandler;
import io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler;
import io.jenkins.plugins.aiagentjob.grokbuild.GrokBuildAgentHandler;
import io.jenkins.plugins.aiagentjob.kiro.KiroAgentHandler;
import io.jenkins.plugins.aiagentjob.opencode.OpenCodeAgentHandler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class AiAgentCommandFactoryTest {

    private static AiAgentBuilder createProject(AiAgentTypeHandler handler) {
        AiAgentBuilder project = new AiAgentBuilder();
        project.setAgent(handler);
        return project;
    }

    private static List<AiAgentTypeHandler> allHandlers() {
        List<AiAgentTypeHandler> handlers = new ArrayList<>();
        handlers.add(new ClaudeCodeAgentHandler());
        handlers.add(new CodexAgentHandler());
        handlers.add(new CursorAgentHandler());
        handlers.add(new OpenCodeAgentHandler());
        handlers.add(new GeminiCliAgentHandler());
        handlers.add(new AntigravityAgentHandler());
        handlers.add(new GrokBuildAgentHandler());
        handlers.add(new KiroAgentHandler());
        return handlers;
    }

    // ======================== Claude Code Command Tests ========================

    @Test
    void claudeCode_basicCommand() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setPrompt("Hello world");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "Hello world");

        assertEquals("npx", cmd.get(0), "Should start with npx");
        assertTrue(cmd.contains("-y"), "Should have -y");
        assertTrue(cmd.contains("@anthropic-ai/claude-code"), "Should have claude-code package");
        assertTrue(cmd.contains("-p"), "Should have -p flag");
        assertTrue(cmd.contains("Hello world"), "Should have prompt");
        assertTrue(cmd.contains("--output-format=stream-json"), "Should have stream-json output");
        assertTrue(cmd.contains("--verbose"), "Should have --verbose");
        assertTrue(
                cmd.contains("--no-session-persistence"), "Should not persist CI prompt sessions");
        assertFalse(
                cmd.contains("--input-format=stream-json"),
                "Should NOT have --input-format=stream-json (not interactive)");
    }

    @Test
    void claudeCode_customExecutableUsesNativeCommandShape() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExecutablePath("/opt/agents/claude");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test prompt");

        assertEquals("/opt/agents/claude", cmd.get(0));
        assertFalse(cmd.contains("-y"));
        assertFalse(cmd.contains("@anthropic-ai/claude-code"));
        assertTrue(cmd.contains("--no-session-persistence"));
    }

    @Test
    void claudeCode_customNpxExecutableKeepsPackageArguments() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExecutablePath("/usr/bin/npx");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test prompt");

        assertEquals("/usr/bin/npx", cmd.get(0));
        assertTrue(cmd.contains("-y"));
        assertTrue(cmd.contains("@anthropic-ai/claude-code"));
    }

    @Test
    void claudeCode_yoloMode() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test prompt");

        assertTrue(
                cmd.contains("--dangerously-skip-permissions"),
                "Should have --dangerously-skip-permissions");
        assertFalse(
                cmd.contains("--permission-mode=default"),
                "Should NOT have --permission-mode=default");
    }

    @Test
    void claudeCode_manualApprovalsAreRejected() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setRequireApprovals(true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test prompt"));

        assertTrue(error.getMessage().contains("ACP-capable"));
    }

    @Test
    void claudeCode_withModel() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setModel("claude-opus-4");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("claude-opus-4", cmd.get(modelIdx + 1));
    }

    @Test
    void claudeCode_withReasoningEffort() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setReasoningEffort("xhigh");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int effortIdx = cmd.indexOf("--effort");
        assertTrue(effortIdx >= 0, "Should have --effort");
        assertEquals("xhigh", cmd.get(effortIdx + 1));
    }

    @Test
    void claudeCode_withModelReasoningSuffix() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setModel("claude-opus-4:high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("claude-opus-4", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("high", cmd.get(cmd.indexOf("--effort") + 1));
    }

    @Test
    void claudeCode_preservesUnsupportedReasoningSuffix() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setModel("claude-opus-4:ultra");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("claude-opus-4:ultra", cmd.get(cmd.indexOf("--model") + 1));
        assertFalse(cmd.contains("--effort"));
    }

    // ======================== Codex Command Tests ========================

    @Test
    void codex_basicCommand() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "fix the bug");

        assertEquals("codex", cmd.get(0));
        assertTrue(cmd.indexOf("exec") > 0, "Should run codex exec");
        assertTrue(cmd.contains("--ephemeral"), "Should not persist CI rollout files");
        assertTrue(cmd.contains("--json"), "Should have --json for JSONL output");
        assertTrue(
                cmd.contains("--skip-git-repo-check"),
                "Should have --skip-git-repo-check for CI environments");
        assertTrue(cmd.contains("fix the bug"), "Should have prompt at end");
    }

    @Test
    void codex_yoloMode() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(
                cmd.contains("--dangerously-bypass-approvals-and-sandbox"),
                "Should have --dangerously-bypass-approvals-and-sandbox");
        assertFalse(cmd.contains("--sandbox"), "Should NOT have --sandbox");
        assertFalse(
                cmd.contains("--ask-for-approval"),
                "Should NOT have --ask-for-approval in yolo mode");
    }

    @Test
    void codex_defaultMode() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setYoloMode(false);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--sandbox"), "Should have --sandbox");
        assertTrue(cmd.contains("workspace-write"), "Should have workspace-write");
        int approvalIdx = cmd.indexOf("--ask-for-approval");
        assertTrue(approvalIdx > 0, "Should have --ask-for-approval for headless execution");
        assertEquals("never", cmd.get(approvalIdx + 1));
        assertTrue(cmd.indexOf("--sandbox") < cmd.indexOf("exec"), "--sandbox is a global flag");
        assertTrue(approvalIdx < cmd.indexOf("exec"), "--ask-for-approval is a global flag");
        assertFalse(cmd.contains("--full-auto"), "Should not use removed --full-auto flag");
    }

    @Test
    void codex_manualApprovalsAreRejected() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setRequireApprovals(true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("does not expose"));
    }

    @Test
    void codex_withModel() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setModel("o3");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("o3", cmd.get(modelIdx + 1));
    }

    @Test
    void codex_withReasoningEffort() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setReasoningEffort("high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int configIdx = cmd.indexOf("-c");
        assertTrue(configIdx > 0, "Should have config override");
        assertEquals("model_reasoning_effort=\"high\"", cmd.get(configIdx + 1));
        assertTrue(configIdx < cmd.indexOf("exec"), "Codex reasoning effort is a global config");
    }

    @Test
    void codex_withModelReasoningSuffix() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setModel("gpt-5.6-sol:xhigh");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("gpt-5.6-sol", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("model_reasoning_effort=\"xhigh\"", cmd.get(cmd.indexOf("-c") + 1));
    }

    @Test
    void codex_reasoningFieldOverridesModelSuffix() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setModel("gpt-5.6-sol:xhigh");
        project.setReasoningEffort("medium");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("gpt-5.6-sol", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("model_reasoning_effort=\"medium\"", cmd.get(cmd.indexOf("-c") + 1));
    }

    @Test
    void codex_preservesUnsupportedReasoningSuffix() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setModel("gpt-5.6-sol:minimal");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("gpt-5.6-sol:minimal", cmd.get(cmd.indexOf("--model") + 1));
        assertFalse(cmd.contains("-c"));
    }

    @Test
    void codex_promptIsLastArgument() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "refactor this");

        assertEquals("refactor this", cmd.get(cmd.size() - 1), "Prompt should be last argument");
    }

    @Test
    void codex_additionalArgsArePlacedInCodexScopes() {
        CodexAgentHandler codex = new CodexAgentHandler();
        codex.setAdditionalGlobalArgs("--search --profile ci");
        codex.setAdditionalExecArgs("--ephemeral --ignore-user-config --color never");
        AiAgentBuilder project = createProject(codex);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int execIdx = cmd.indexOf("exec");
        int promptIdx = cmd.indexOf("test");
        assertTrue(cmd.indexOf("--search") > 0 && cmd.indexOf("--search") < execIdx);
        assertTrue(cmd.indexOf("--profile") > 0 && cmd.indexOf("--profile") < execIdx);
        assertEquals("ci", cmd.get(cmd.indexOf("--profile") + 1));
        assertTrue(cmd.indexOf("--ephemeral") > execIdx && cmd.indexOf("--ephemeral") < promptIdx);
        assertEquals(1, cmd.stream().filter("--ephemeral"::equals).count());
        assertTrue(
                cmd.indexOf("--ignore-user-config") > execIdx
                        && cmd.indexOf("--ignore-user-config") < promptIdx);
        assertEquals("never", cmd.get(cmd.indexOf("--color") + 1));
    }

    // ======================== Cursor Agent Command Tests ========================

    @Test
    void cursorAgent_basicCommand() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "analyze code");

        assertEquals("agent", cmd.get(0));
        assertTrue(cmd.contains("-p"), "Should have -p for print mode");
        assertTrue(
                cmd.contains("--output-format=stream-json"),
                "Should have --output-format=stream-json");
        assertTrue(cmd.contains("--trust"), "Should have --trust for headless mode");
        assertFalse(cmd.contains("--approve-mcps"), "Should not auto-approve MCP servers");
        assertTrue(cmd.contains("analyze code"), "Should have prompt");
    }

    @Test
    void cursorAgent_yoloMode() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--yolo"), "Should have --yolo");
        assertTrue(cmd.contains("--approve-mcps"), "Yolo mode should approve MCP servers");
    }

    @Test
    void cursorAgent_withModel() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setModel("sonnet-4-thinking");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("sonnet-4-thinking", cmd.get(modelIdx + 1));
    }

    @Test
    void cursorAgent_preservesReasoningLikeModelSuffix() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setModel("cursor-model:high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("cursor-model:high", cmd.get(cmd.indexOf("--model") + 1));
    }

    @Test
    void cursorAgent_manualApprovalsAreRejected() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setRequireApprovals(true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("ACP-capable"));
    }

    // ======================== OpenCode Command Tests ========================

    @Test
    void openCode_basicCommand() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "explain this");

        assertEquals("opencode", cmd.get(0));
        assertEquals("run", cmd.get(1));
        assertTrue(cmd.contains("--format"), "Should have --format");
        assertTrue(cmd.contains("json"), "Should have json");
        assertTrue(cmd.contains("explain this"), "Should have prompt");
    }

    @Test
    void openCode_withModel() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setModel("anthropic/claude-sonnet-4");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("anthropic/claude-sonnet-4", cmd.get(modelIdx + 1));
    }

    @Test
    void openCode_withReasoningEffort() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setReasoningEffort("max");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int variantIdx = cmd.indexOf("--variant");
        assertTrue(variantIdx >= 0, "Should have --variant");
        assertEquals("max", cmd.get(variantIdx + 1));
    }

    @Test
    void openCode_withModelReasoningSuffix() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setModel("openai/gpt-5.6-terra:ultra");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("openai/gpt-5.6-terra", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("ultra", cmd.get(cmd.indexOf("--variant") + 1));
    }

    @Test
    void openCode_preservesNonReasoningModelSuffix() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setModel("openrouter/example/model:free");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("openrouter/example/model:free", cmd.get(cmd.indexOf("--model") + 1));
        assertFalse(cmd.contains("--variant"));
    }

    @Test
    void openCode_preservesEscapedReasoningLikeModelSuffix() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setModel("provider/example/model::high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("provider/example/model:high", cmd.get(cmd.indexOf("--model") + 1));
        assertFalse(cmd.contains("--variant"));
    }

    @Test
    void openCode_acpExecutionMapsRunOptionsToSessionConfig() {
        OpenCodeAgentHandler handler = new OpenCodeAgentHandler();
        AiAgentBuilder project = createProject(handler);
        project.setExecutablePath("/opt/agents/opencode");
        project.setModel("opencode/provider-model");
        project.setReasoningEffort("high");
        project.setExtraArgs("--model override/model --variant=xhigh --format json --pure");

        AiAgentTypeHandler.AcpExecutionSpec execution = handler.buildAcpExecution(project);

        assertEquals(
                List.of("/opt/agents/opencode", "acp", "--pure"),
                AiAgentCommandFactory.applyExecutablePath(
                        execution.getCommand(), project.getExecutablePath()));
        assertEquals("override/model", execution.getModel());
        assertEquals("xhigh", execution.getReasoningEffort());
    }

    @Test
    void openCode_acpExecutionMapsModelReasoningSuffixToSessionConfig() {
        OpenCodeAgentHandler handler = new OpenCodeAgentHandler();
        AiAgentBuilder project = createProject(handler);
        project.setModel("openai/gpt-5.6-sol:xhigh");

        AiAgentTypeHandler.AcpExecutionSpec execution = handler.buildAcpExecution(project);

        assertEquals("openai/gpt-5.6-sol", execution.getModel());
        assertEquals("xhigh", execution.getReasoningEffort());
    }

    @Test
    void openCode_commandOverrideCannotUseManualApprovals() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setRequireApprovals(true);
        project.setCommandOverride("opencode acp --custom");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("command override"));
    }

    // ======================== Grok Build Command Tests ========================

    @Test
    void grokBuild_basicCommand() {
        AiAgentBuilder project = createProject(new GrokBuildAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "review this");

        assertEquals(
                List.of(
                        "grok",
                        "--no-auto-update",
                        "-p",
                        "review this",
                        "--output-format",
                        "streaming-json",
                        "--permission-mode",
                        "auto"),
                cmd);
    }

    @Test
    void grokBuild_mapsYoloModelAndReasoningEffort() {
        AiAgentBuilder project = createProject(new GrokBuildAgentHandler());
        project.setYoloMode(true);
        project.setModel("grok-4.5");
        project.setReasoningEffort("high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--always-approve"));
        assertFalse(cmd.contains("auto"));
        assertEquals("grok-4.5", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("high", cmd.get(cmd.indexOf("--reasoning-effort") + 1));
    }

    @Test
    void grokBuild_modelSuffixSetsReasoningEffortForHeadlessAndAcp() {
        GrokBuildAgentHandler handler = new GrokBuildAgentHandler();
        AiAgentBuilder project = createProject(handler);
        project.setModel("grok-4.5:medium");

        List<String> command = AiAgentCommandFactory.buildDefaultCommand(project, "test");
        AiAgentTypeHandler.AcpExecutionSpec execution = handler.buildAcpExecution(project);
        List<String> acpCommand = execution.getCommand();

        assertEquals("grok-4.5", command.get(command.indexOf("--model") + 1));
        assertEquals("medium", command.get(command.indexOf("--reasoning-effort") + 1));
        assertEquals("grok-4.5", acpCommand.get(acpCommand.indexOf("--model") + 1));
        assertEquals("medium", acpCommand.get(acpCommand.indexOf("--reasoning-effort") + 1));
    }

    @Test
    void grokBuild_acpUsesAuthenticationAndForcesInteractivePermissions() {
        GrokBuildAgentHandler handler = new GrokBuildAgentHandler();
        AiAgentBuilder project = createProject(handler);
        project.setModel("grok-4.5");
        project.setReasoningEffort("high");
        project.setApiEnvVarName("GROK_API_TOKEN");
        project.setSetupScript("export XAI_API_KEY=\"$GROK_API_TOKEN\"");
        project.setExtraArgs(
                "--model grok-override --effort=medium --output-format json "
                        + "--always-approve --permission-mode bypassPermissions "
                        + "--dangerously-skip-permissions=true --allow Bash "
                        + "--allowedTools=Edit --disable-web-search --tools execute "
                        + "--disallowed-tools web_search --max-turns 4 "
                        + "--plugin-dir /opt/grok-plugin --no-leader");

        AiAgentTypeHandler.AcpExecutionSpec execution = handler.buildAcpExecution(project);

        assertEquals(
                List.of(
                        "grok",
                        "--no-auto-update",
                        "--permission-mode",
                        "default",
                        "--disable-web-search",
                        "--tools",
                        "execute",
                        "--disallowed-tools",
                        "web_search",
                        "--max-turns",
                        "4",
                        "agent",
                        "--model",
                        "grok-override",
                        "--reasoning-effort",
                        "medium",
                        "--plugin-dir",
                        "/opt/grok-plugin",
                        "--no-leader",
                        "stdio"),
                execution.getCommand());
        assertEquals("", execution.getModel());
        assertEquals("", execution.getReasoningEffort());
        assertEquals("xai.api_key", execution.getAuthenticationMethods().get("GROK_API_TOKEN"));
        assertEquals(
                List.of("xai.api_key", "cached_token"),
                execution.getFallbackAuthenticationMethods());
    }

    // ======================== Gemini CLI Command Tests ========================

    @Test
    void geminiCli_basicCommand() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "summarize project");

        assertEquals("gemini", cmd.get(0));
        assertTrue(cmd.contains("-p"), "Should have -p for prompt");
        assertTrue(cmd.contains("--output-format"), "Should have --output-format");
        assertTrue(cmd.contains("stream-json"), "Should have stream-json");
        assertTrue(cmd.contains("summarize project"), "Should have prompt");
    }

    @Test
    void geminiCli_yoloMode() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--yolo"), "Should have --yolo");
    }

    @Test
    void geminiCli_manualApprovalsAreRejected() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setRequireApprovals(true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("ACP-capable"));
    }

    @Test
    void geminiCli_withModel() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setModel("gemini-2.5-flash");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("-m");
        assertTrue(modelIdx >= 0, "Should have -m");
        assertEquals("gemini-2.5-flash", cmd.get(modelIdx + 1));
    }

    @Test
    void geminiCli_preservesReasoningLikeModelSuffix() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setModel("gemini-model:high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("gemini-model:high", cmd.get(cmd.indexOf("-m") + 1));
    }

    // ======================== Antigravity CLI ========================

    @Test
    void antigravity_basicCommand() {
        AiAgentBuilder project = createProject(new AntigravityAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "summarize project");

        assertEquals("agy", cmd.get(0));
        assertTrue(cmd.contains("--print"));
        assertTrue(cmd.contains("--output-format"));
        assertTrue(cmd.contains("stream-json"));
        assertTrue(cmd.contains("summarize project"));
    }

    @Test
    void antigravity_yoloMode() {
        AiAgentBuilder project = createProject(new AntigravityAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--dangerously-skip-permissions"));
    }

    @Test
    void antigravity_manualApprovalsAreRejected() {
        AiAgentBuilder project = createProject(new AntigravityAgentHandler());
        project.setRequireApprovals(true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("ACP-capable"));
    }

    @Test
    void antigravity_withModelAndReasoningEffort() {
        AiAgentBuilder project = createProject(new AntigravityAgentHandler());
        project.setModel("gemini-3.6-flash-high");
        project.setReasoningEffort("high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        int effortIdx = cmd.indexOf("--effort");
        assertTrue(modelIdx >= 0);
        assertEquals("gemini-3.6-flash-high", cmd.get(modelIdx + 1));
        assertTrue(effortIdx >= 0);
        assertEquals("high", cmd.get(effortIdx + 1));
    }

    @Test
    void antigravity_modelSuffixSetsReasoningEffort() {
        AiAgentBuilder project = createProject(new AntigravityAgentHandler());
        project.setModel("gemini-3.6-flash:high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("gemini-3.6-flash", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("high", cmd.get(cmd.indexOf("--effort") + 1));
    }

    @Test
    void antigravity_credentialRequiresExplicitEnvironmentVariable() {
        AiAgentBuilder project = createProject(new AntigravityAgentHandler());
        project.setApiCredentialsId("custom-auth");

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("node-level Google authentication"));

        project.setApiEnvVarName("GOOGLE_APPLICATION_CREDENTIALS");
        assertEquals("agy", AiAgentCommandFactory.buildDefaultCommand(project, "test").get(0));
    }

    // ======================== Kiro CLI ========================

    @Test
    void kiroCli_basicCommand() {
        AiAgentBuilder project = createProject(new KiroAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "review this");

        assertEquals("kiro-cli", cmd.get(0));
        assertTrue(cmd.contains("chat"), "Should have chat subcommand");
        assertTrue(cmd.contains("--no-interactive"), "Should have --no-interactive");
        assertTrue(cmd.contains("--wrap"), "Should have --wrap");
        assertTrue(cmd.contains("never"), "Should set wrap to never");
        assertTrue(cmd.contains("--trust-all-tools"), "Should have --trust-all-tools");
        assertTrue(cmd.contains("review this"), "Should have prompt");
    }

    @Test
    void kiroCli_yoloMode() {
        AiAgentBuilder project = createProject(new KiroAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--trust-all-tools"), "Should trust all tools in yolo mode");
    }

    @Test
    void kiroCli_manualApprovalsAreRejected() {
        AiAgentBuilder project = createProject(new KiroAgentHandler());
        project.setRequireApprovals(true);

        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AiAgentCommandFactory.buildDefaultCommand(project, "test"));

        assertTrue(error.getMessage().contains("ACP-capable"));
    }

    @Test
    void kiroCli_withModel() {
        AiAgentBuilder project = createProject(new KiroAgentHandler());
        project.setModel("gpt-5.6-sol");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue(modelIdx >= 0, "Should have --model");
        assertEquals("gpt-5.6-sol", cmd.get(modelIdx + 1));
    }

    @Test
    void kiroCli_withReasoningEffort() {
        AiAgentBuilder project = createProject(new KiroAgentHandler());
        project.setReasoningEffort("high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int effortIdx = cmd.indexOf("--effort");
        assertTrue(effortIdx >= 0, "Should have --effort");
        assertEquals("high", cmd.get(effortIdx + 1));
    }

    @Test
    void kiroCli_modelSuffixSetsReasoningEffort() {
        AiAgentBuilder project = createProject(new KiroAgentHandler());
        project.setModel("gpt-5.6-sol:high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("gpt-5.6-sol", cmd.get(cmd.indexOf("--model") + 1));
        assertEquals("high", cmd.get(cmd.indexOf("--effort") + 1));
    }

    @Test
    void kiroCli_preservesUnsupportedReasoningSuffix() {
        AiAgentBuilder project = createProject(new KiroAgentHandler());
        project.setModel("custom-model:ultra");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertEquals("custom-model:ultra", cmd.get(cmd.indexOf("--model") + 1));
        assertFalse(cmd.contains("--effort"));
    }

    @Test
    void kiroCli_acpExecution() {
        KiroAgentHandler handler = new KiroAgentHandler();
        AiAgentBuilder project = createProject(handler);
        project.setModel("gpt-5.6-sol");
        project.setReasoningEffort("xhigh");

        AiAgentTypeHandler.AcpExecutionSpec execution = handler.buildAcpExecution(project);

        assertEquals(List.of("kiro-cli", "acp"), execution.getCommand());
        assertEquals("gpt-5.6-sol", execution.getModel());
        assertEquals("xhigh", execution.getReasoningEffort());
    }

    // ======================== Extra Args Tests ========================

    @Test
    void extraArgs_appendedToCommand() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExtraArgs("--max-budget-usd 5 --effort high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(cmd.contains("--max-budget-usd"), "Should contain --max-budget-usd");
        assertTrue(cmd.contains("5"), "Should contain 5");
        assertTrue(cmd.contains("--effort"), "Should contain --effort");
        assertTrue(cmd.contains("high"), "Should contain high");
    }

    @Test
    void extraArgs_emptyDoesNotAddTokens() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExtraArgs("   ");
        AiAgentBuilder baseline = createProject(new ClaudeCodeAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");
        List<String> baselineCmd = AiAgentCommandFactory.buildDefaultCommand(baseline, "test");

        assertEquals(baselineCmd, cmd);
    }

    // ======================== Environment Variable Parsing
    // ========================

    @Test
    void parseEnvironmentVariables_basic() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables("KEY1=value1\nKEY2=value2");
        assertEquals(2, vars.size());
        assertEquals("value1", vars.get("KEY1"));
        assertEquals("value2", vars.get("KEY2"));
    }

    @Test
    void parseEnvironmentVariables_handlesCommentsAndBlanks() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables(
                        "# comment\nKEY=val\n\n  # another comment\n  ");
        assertEquals(1, vars.size());
        assertEquals("val", vars.get("KEY"));
    }

    @Test
    void parseEnvironmentVariables_handlesEqualsInValue() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables(
                        "DATABASE_URL=postgres://user:pass@host/db?sslmode=require");
        assertEquals(1, vars.size());
        assertEquals("postgres://user:pass@host/db?sslmode=require", vars.get("DATABASE_URL"));
    }

    @Test
    void parseEnvironmentVariables_handlesNullAndEmpty() {
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables(null).isEmpty());
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables("").isEmpty());
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables("   ").isEmpty());
    }

    @Test
    void parseEnvironmentVariables_handlesWindowsLineEndings() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables("A=1\r\nB=2\r\n");
        assertEquals(2, vars.size());
        assertEquals("1", vars.get("A"));
        assertEquals("2", vars.get("B"));
    }

    // ======================== Model Without Value ========================

    @Test
    void allAgents_supportExecutablePathOverride() {
        for (AiAgentTypeHandler handler : allHandlers()) {
            AiAgentBuilder project = createProject(handler);
            project.setExecutablePath("/opt/agents/" + handler.getId());

            List<String> command = AiAgentCommandFactory.buildDefaultCommand(project, "test");

            assertEquals(project.getExecutablePath(), command.get(0));
        }
    }

    @Test
    void allAgents_noModelByDefault() {
        for (AiAgentTypeHandler handler : allHandlers()) {
            AiAgentBuilder project = createProject(handler);
            List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");
            assertFalse(
                    cmd.contains("--model") || cmd.contains("-m"),
                    "Agent " + handler.getId() + " should not add --model when empty");
        }
    }

    @Test
    void allAgents_havePromptInCommand() {
        for (AiAgentTypeHandler handler : allHandlers()) {
            AiAgentBuilder project = createProject(handler);
            List<String> cmd =
                    AiAgentCommandFactory.buildDefaultCommand(
                            project, "unique-prompt-" + handler.getId());
            assertTrue(
                    cmd.contains("unique-prompt-" + handler.getId()),
                    "Agent " + handler.getId() + " should have prompt in command");
        }
    }
}
