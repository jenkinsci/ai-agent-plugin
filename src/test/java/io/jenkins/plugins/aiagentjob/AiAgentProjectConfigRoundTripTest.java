package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class AiAgentProjectConfigRoundTripTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void preservesConfiguredFields() throws Exception {
        AiAgentProject project = jenkins.createProject(AiAgentProject.class, "ai-config-roundtrip");
        project.setAgentType(AgentType.GEMINI_CLI);
        project.setPrompt("Summarize this repository.");
        project.setModel("gemini-2.5-pro");
        project.setWorkingDirectory("src");
        project.setYoloMode(false);
        project.setRequireApprovals(true);
        project.setApprovalTimeoutSeconds(42);
        project.setCommandOverride("echo '{\"type\":\"assistant\",\"message\":\"hi\"}'");
        project.setExtraArgs("--foo bar");
        project.setEnvironmentVariables("FOO=bar\nHELLO=world");
        project.setSetupScript("export PATH=$HOME/.local/bin:$PATH\nnpm install");
        project.setCodexCustomConfigEnabled(true);
        project.setCodexCustomConfigToml("[mcp_servers.demo]\ncommand = \"npx\"");
        project.setFailOnAgentError(false);
        project.save();

        jenkins.configRoundtrip(project);

        assertEquals(AgentType.GEMINI_CLI, project.getAgentType());
        assertEquals("Summarize this repository.", project.getPrompt());
        assertEquals("gemini-2.5-pro", project.getModel());
        assertEquals("src", project.getWorkingDirectory());
        assertFalse(project.isYoloMode());
        assertTrue(project.isRequireApprovals());
        assertEquals(42, project.getApprovalTimeoutSeconds());
        assertEquals(
                "echo '{\"type\":\"assistant\",\"message\":\"hi\"}'", project.getCommandOverride());
        assertEquals("--foo bar", project.getExtraArgs());
        assertEquals("FOO=bar\nHELLO=world", project.getEnvironmentVariables());
        assertEquals("export PATH=$HOME/.local/bin:$PATH\nnpm install", project.getSetupScript());
        assertTrue(project.isCodexCustomConfigEnabled());
        assertEquals("[mcp_servers.demo]\ncommand = \"npx\"", project.getCodexCustomConfigToml());
        assertFalse(project.isFailOnAgentError());
    }

    @Test
    public void configureEntries_usesExternalResourcesForCodexToggle() throws Exception {
        String jelly =
                readResource(
                        "/io/jenkins/plugins/aiagentjob/AiAgentProject/configure-entries.jelly");
        assertTrue(
                "configure-entries.jelly should load adjunct resources",
                jelly.contains(
                        "<st:adjunct includes=\"io.jenkins.plugins.aiagentjob.AiAgentProject.config_codex_fields\""));
        assertFalse(
                "configure-entries.jelly should not contain inline style tags",
                jelly.contains("<style"));
        assertFalse(
                "configure-entries.jelly should not contain inline script tags",
                jelly.contains("<script"));
        assertFalse(
                "configure-entries.jelly should not contain inline style attributes",
                jelly.contains(" style=")
                        || jelly.contains("style=\"")
                        || jelly.contains("style='"));
    }

    @Test
    public void configCodexFieldResources_exist() {
        assertNotNull(
                "codex config toggle resource should exist",
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/AiAgentProject/config_codex_fields.js"));
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull("Resource should exist: " + path, in);
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}
