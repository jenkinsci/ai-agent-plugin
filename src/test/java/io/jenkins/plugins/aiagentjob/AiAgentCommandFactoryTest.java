package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AiAgentCommandFactoryTest {

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
        return handlers;
    }

    // ======================== Claude Code Command Tests ========================

    @Test
    public void claudeCode_basicCommand() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setPrompt("Hello world");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "Hello world");

        assertTrue("Should start with npx", cmd.get(0).equals("npx"));
        assertTrue("Should have -y", cmd.contains("-y"));
        assertTrue("Should have claude-code package", cmd.contains("@anthropic-ai/claude-code"));
        assertTrue("Should have -p flag", cmd.contains("-p"));
        assertTrue("Should have prompt", cmd.contains("Hello world"));
        assertTrue("Should have stream-json output", cmd.contains("--output-format=stream-json"));
        assertTrue("Should have --verbose", cmd.contains("--verbose"));
        assertFalse(
                "Should NOT have --input-format=stream-json (not interactive)",
                cmd.contains("--input-format=stream-json"));
    }

    @Test
    public void claudeCode_yoloMode() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test prompt");

        assertTrue(
                "Should have --dangerously-skip-permissions",
                cmd.contains("--dangerously-skip-permissions"));
        assertFalse(
                "Should NOT have --permission-mode=default",
                cmd.contains("--permission-mode=default"));
    }

    @Test
    public void claudeCode_approvalsMode() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setRequireApprovals(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test prompt");

        assertTrue(
                "Should have --permission-mode=default", cmd.contains("--permission-mode=default"));
        assertFalse(
                "Should NOT have --dangerously-skip-permissions",
                cmd.contains("--dangerously-skip-permissions"));
    }

    @Test
    public void claudeCode_withModel() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setModel("claude-opus-4");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue("Should have --model", modelIdx >= 0);
        assertEquals("claude-opus-4", cmd.get(modelIdx + 1));
    }

    // ======================== Codex Command Tests ========================

    @Test
    public void codex_basicCommand() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "fix the bug");

        assertEquals("codex", cmd.get(0));
        assertEquals("exec", cmd.get(1));
        assertTrue("Should have --json for JSONL output", cmd.contains("--json"));
        assertTrue(
                "Should have --skip-git-repo-check for CI environments",
                cmd.contains("--skip-git-repo-check"));
        assertTrue("Should have prompt at end", cmd.contains("fix the bug"));
    }

    @Test
    public void codex_yoloMode() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue(
                "Should have --dangerously-bypass-approvals-and-sandbox",
                cmd.contains("--dangerously-bypass-approvals-and-sandbox"));
        assertFalse("Should NOT have --sandbox", cmd.contains("--sandbox"));
        assertFalse("Should NOT have --full-auto in yolo mode", cmd.contains("--full-auto"));
    }

    @Test
    public void codex_defaultMode() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setYoloMode(false);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue("Should have --sandbox", cmd.contains("--sandbox"));
        assertTrue("Should have workspace-write", cmd.contains("workspace-write"));
        assertTrue("Should have --full-auto for headless execution", cmd.contains("--full-auto"));
        assertFalse(
                "Should NOT have --ask-for-approval (not valid for codex exec)",
                cmd.contains("--ask-for-approval"));
    }

    @Test
    public void codex_withModel() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());
        project.setModel("o3");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue("Should have --model", modelIdx >= 0);
        assertEquals("o3", cmd.get(modelIdx + 1));
    }

    @Test
    public void codex_promptIsLastArgument() {
        AiAgentBuilder project = createProject(new CodexAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "refactor this");

        assertEquals("Prompt should be last argument", "refactor this", cmd.get(cmd.size() - 1));
    }

    // ======================== Cursor Agent Command Tests ========================

    @Test
    public void cursorAgent_basicCommand() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "analyze code");

        assertEquals("agent", cmd.get(0));
        assertTrue("Should have -p for print mode", cmd.contains("-p"));
        assertTrue(
                "Should have --output-format=stream-json",
                cmd.contains("--output-format=stream-json"));
        assertTrue("Should have --trust for headless mode", cmd.contains("--trust"));
        assertTrue("Should have --approve-mcps for headless mode", cmd.contains("--approve-mcps"));
        assertTrue("Should have prompt", cmd.contains("analyze code"));
    }

    @Test
    public void cursorAgent_yoloMode() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue("Should have --yolo", cmd.contains("--yolo"));
    }

    @Test
    public void cursorAgent_withModel() {
        AiAgentBuilder project = createProject(new CursorAgentHandler());
        project.setModel("sonnet-4-thinking");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue("Should have --model", modelIdx >= 0);
        assertEquals("sonnet-4-thinking", cmd.get(modelIdx + 1));
    }

    // ======================== OpenCode Command Tests ========================

    @Test
    public void openCode_basicCommand() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "explain this");

        assertEquals("opencode", cmd.get(0));
        assertEquals("run", cmd.get(1));
        assertTrue("Should have --format", cmd.contains("--format"));
        assertTrue("Should have json", cmd.contains("json"));
        assertTrue("Should have prompt", cmd.contains("explain this"));
    }

    @Test
    public void openCode_withModel() {
        AiAgentBuilder project = createProject(new OpenCodeAgentHandler());
        project.setModel("anthropic/claude-sonnet-4");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("--model");
        assertTrue("Should have --model", modelIdx >= 0);
        assertEquals("anthropic/claude-sonnet-4", cmd.get(modelIdx + 1));
    }

    // ======================== Gemini CLI Command Tests ========================

    @Test
    public void geminiCli_basicCommand() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "summarize project");

        assertEquals("gemini", cmd.get(0));
        assertTrue("Should have -p for prompt", cmd.contains("-p"));
        assertTrue("Should have --output-format", cmd.contains("--output-format"));
        assertTrue("Should have stream-json", cmd.contains("stream-json"));
        assertTrue("Should have prompt", cmd.contains("summarize project"));
    }

    @Test
    public void geminiCli_yoloMode() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setYoloMode(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue("Should have --yolo", cmd.contains("--yolo"));
    }

    @Test
    public void geminiCli_withApprovals() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setRequireApprovals(true);

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue("Should have --approval-mode", cmd.contains("--approval-mode"));
        assertTrue("Should have default", cmd.contains("default"));
    }

    @Test
    public void geminiCli_withModel() {
        AiAgentBuilder project = createProject(new GeminiCliAgentHandler());
        project.setModel("gemini-2.5-flash");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        int modelIdx = cmd.indexOf("-m");
        assertTrue("Should have -m", modelIdx >= 0);
        assertEquals("gemini-2.5-flash", cmd.get(modelIdx + 1));
    }

    // ======================== Extra Args Tests ========================

    @Test
    public void extraArgs_appendedToCommand() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExtraArgs("--max-budget-usd 5 --effort high");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");

        assertTrue("Should contain --max-budget-usd", cmd.contains("--max-budget-usd"));
        assertTrue("Should contain 5", cmd.contains("5"));
        assertTrue("Should contain --effort", cmd.contains("--effort"));
        assertTrue("Should contain high", cmd.contains("high"));
    }

    @Test
    public void extraArgs_emptyDoesNotAddTokens() {
        AiAgentBuilder project = createProject(new ClaudeCodeAgentHandler());
        project.setExtraArgs("   ");

        List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");
        int verboseIdx = cmd.indexOf("--verbose");
        assertEquals(
                "Last regular arg should be the last element or close", verboseIdx, cmd.size() - 1);
    }

    // ======================== Environment Variable Parsing
    // ========================

    @Test
    public void parseEnvironmentVariables_basic() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables("KEY1=value1\nKEY2=value2");
        assertEquals(2, vars.size());
        assertEquals("value1", vars.get("KEY1"));
        assertEquals("value2", vars.get("KEY2"));
    }

    @Test
    public void parseEnvironmentVariables_handlesCommentsAndBlanks() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables(
                        "# comment\nKEY=val\n\n  # another comment\n  ");
        assertEquals(1, vars.size());
        assertEquals("val", vars.get("KEY"));
    }

    @Test
    public void parseEnvironmentVariables_handlesEqualsInValue() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables(
                        "DATABASE_URL=postgres://user:pass@host/db?sslmode=require");
        assertEquals(1, vars.size());
        assertEquals("postgres://user:pass@host/db?sslmode=require", vars.get("DATABASE_URL"));
    }

    @Test
    public void parseEnvironmentVariables_handlesNullAndEmpty() {
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables(null).isEmpty());
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables("").isEmpty());
        assertTrue(AiAgentCommandFactory.parseEnvironmentVariables("   ").isEmpty());
    }

    @Test
    public void parseEnvironmentVariables_handlesWindowsLineEndings() {
        Map<String, String> vars =
                AiAgentCommandFactory.parseEnvironmentVariables("A=1\r\nB=2\r\n");
        assertEquals(2, vars.size());
        assertEquals("1", vars.get("A"));
        assertEquals("2", vars.get("B"));
    }

    // ======================== Command As String ========================

    @Test
    public void commandAsString_joinsTokens() {
        String result = AiAgentCommandFactory.commandAsString(List.of("echo", "hello", "world"));
        assertEquals("echo hello world", result);
    }

    @Test
    public void commandAsString_quotesSpaces() {
        String result = AiAgentCommandFactory.commandAsString(List.of("echo", "hello world"));
        assertEquals("echo \"hello world\"", result);
    }

    @Test
    public void commandAsString_escapesQuotes() {
        String result = AiAgentCommandFactory.commandAsString(List.of("echo", "say \"hi\""));
        assertEquals("echo \"say \\\"hi\\\"\"", result);
    }

    // ======================== Model Without Value ========================

    @Test
    public void allAgents_noModelByDefault() {
        for (AiAgentTypeHandler handler : allHandlers()) {
            AiAgentBuilder project = createProject(handler);
            List<String> cmd = AiAgentCommandFactory.buildDefaultCommand(project, "test");
            assertFalse(
                    "Agent " + handler.getId() + " should not add --model when empty",
                    cmd.contains("--model") || cmd.contains("-m"));
        }
    }

    @Test
    public void allAgents_havePromptInCommand() {
        for (AiAgentTypeHandler handler : allHandlers()) {
            AiAgentBuilder project = createProject(handler);
            List<String> cmd =
                    AiAgentCommandFactory.buildDefaultCommand(
                            project, "unique-prompt-" + handler.getId());
            assertTrue(
                    "Agent " + handler.getId() + " should have prompt in command",
                    cmd.contains("unique-prompt-" + handler.getId()));
        }
    }
}
