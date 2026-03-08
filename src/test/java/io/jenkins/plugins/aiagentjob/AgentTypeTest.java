package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AgentTypeTest {

    @Test
    public void fromString_matchesEnumNames() {
        assertEquals(AgentType.CLAUDE_CODE, AgentType.fromString("CLAUDE_CODE"));
        assertEquals(AgentType.CODEX, AgentType.fromString("CODEX"));
        assertEquals(AgentType.CURSOR_AGENT, AgentType.fromString("CURSOR_AGENT"));
        assertEquals(AgentType.OPENCODE, AgentType.fromString("OPENCODE"));
        assertEquals(AgentType.GEMINI_CLI, AgentType.fromString("GEMINI_CLI"));
    }

    @Test
    public void fromString_caseInsensitive() {
        assertEquals(AgentType.CLAUDE_CODE, AgentType.fromString("claude_code"));
        assertEquals(AgentType.CODEX, AgentType.fromString("codex"));
        assertEquals(AgentType.GEMINI_CLI, AgentType.fromString("gemini_cli"));
    }

    @Test
    public void fromString_trimsWhitespace() {
        assertEquals(AgentType.CODEX, AgentType.fromString("  CODEX  "));
    }

    @Test
    public void fromString_defaultsToClaudeCode() {
        assertEquals(AgentType.CLAUDE_CODE, AgentType.fromString(null));
        assertEquals(AgentType.CLAUDE_CODE, AgentType.fromString("unknown_agent"));
        assertEquals(AgentType.CLAUDE_CODE, AgentType.fromString(""));
    }

    @Test
    public void displayNames_areReadable() {
        assertEquals("Claude Code", AgentType.CLAUDE_CODE.getDisplayName());
        assertEquals("Codex CLI", AgentType.CODEX.getDisplayName());
        assertEquals("Cursor Agent", AgentType.CURSOR_AGENT.getDisplayName());
        assertEquals("OpenCode", AgentType.OPENCODE.getDisplayName());
        assertEquals("Gemini CLI", AgentType.GEMINI_CLI.getDisplayName());
        assertEquals("Codex CLI", AgentType.CODEX.toString());
    }

    @Test
    public void allValues_haveNonEmptyDisplayName() {
        for (AgentType type : AgentType.values()) {
            String name = type.getDisplayName();
            assert name != null && !name.isEmpty() : "Display name should not be empty for " + type;
        }
    }

    @Test
    public void defaultApiKeyEnvVars_correctForEachAgent() {
        assertEquals("ANTHROPIC_API_KEY", AgentType.CLAUDE_CODE.getDefaultApiKeyEnvVar());
        assertEquals("OPENAI_API_KEY", AgentType.CODEX.getDefaultApiKeyEnvVar());
        assertEquals("CURSOR_API_KEY", AgentType.CURSOR_AGENT.getDefaultApiKeyEnvVar());
        assertEquals("OPENAI_API_KEY", AgentType.OPENCODE.getDefaultApiKeyEnvVar());
        assertEquals("GEMINI_API_KEY", AgentType.GEMINI_CLI.getDefaultApiKeyEnvVar());
    }

    @Test
    public void allValues_haveNonEmptyApiKeyEnvVar() {
        for (AgentType type : AgentType.values()) {
            String envVar = type.getDefaultApiKeyEnvVar();
            assert envVar != null && !envVar.isEmpty()
                    : "API key env var should not be empty for " + type;
        }
    }
}
