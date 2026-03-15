package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleProject;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class AiAgentBuilderConfigRoundTripTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void preservesConfiguredFields() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("ai-config-roundtrip");
        AiAgentBuilder builder = new AiAgentBuilder();
        builder.setAgent(new GeminiCliAgentHandler());
        builder.setPrompt("Summarize this repository.");
        builder.setModel("gemini-2.5-pro");
        builder.setWorkingDirectory("src");
        builder.setYoloMode(false);
        builder.setRequireApprovals(true);
        builder.setApprovalTimeoutSeconds(42);
        builder.setCommandOverride("echo '{\"type\":\"assistant\",\"message\":\"hi\"}'");
        builder.setExtraArgs("--foo bar");
        builder.setEnvironmentVariables("FOO=bar\nHELLO=world");
        builder.setSetupScript("export PATH=$HOME/.local/bin:$PATH\nnpm install");
        builder.setFailOnAgentError(false);
        project.getBuildersList().add(builder);
        project.save();

        jenkins.configRoundtrip(project);

        AiAgentBuilder reloaded = (AiAgentBuilder) project.getBuildersList().get(0);
        assertEquals("GEMINI_CLI", reloaded.getAgent().getId());
        assertEquals("Summarize this repository.", reloaded.getPrompt());
        assertEquals("gemini-2.5-pro", reloaded.getModel());
        assertEquals("src", reloaded.getWorkingDirectory());
        assertFalse(reloaded.isYoloMode());
        assertTrue(reloaded.isRequireApprovals());
        assertEquals(42, reloaded.getApprovalTimeoutSeconds());
        assertEquals(
                "echo '{\"type\":\"assistant\",\"message\":\"hi\"}'",
                reloaded.getCommandOverride());
        assertEquals("--foo bar", reloaded.getExtraArgs());
        assertEquals("FOO=bar\nHELLO=world", reloaded.getEnvironmentVariables());
        assertEquals("export PATH=$HOME/.local/bin:$PATH\nnpm install", reloaded.getSetupScript());
        assertFalse(reloaded.isFailOnAgentError());
    }

    @Test
    public void commandOverride_normalizesWrappedLineBreaks() {
        AiAgentBuilder builder = new AiAgentBuilder();
        builder.setCommandOverride("/opt/opencode\n  --model gpt-5\n  --json");
        assertEquals("/opt/opencode --model gpt-5 --json", builder.getCommandOverride());
    }

    @Test
    public void configJelly_usesDescriptorSelectorWithoutInlineScripts() throws Exception {
        String jelly = readResource("/io/jenkins/plugins/aiagentjob/AiAgentBuilder/config.jelly");
        assertTrue(
                "config.jelly should use descriptor selector",
                jelly.contains(
                        "<f:dropdownDescriptorSelector title=\"Agent Type\" field=\"agent\" descriptors=\"${descriptor.agentDescriptors}\" />"));
        assertTrue("command override should use textbox", jelly.contains("<f:textbox />"));
        assertFalse(
                "command override should not use expandableTextbox",
                jelly.contains("<f:expandableTextbox"));
        assertFalse("config.jelly should not contain inline style tags", jelly.contains("<style"));
        assertFalse(
                "config.jelly should not contain inline script tags", jelly.contains("<script"));
        assertFalse(
                "config.jelly should not contain inline style attributes",
                jelly.contains(" style=")
                        || jelly.contains("style=\"")
                        || jelly.contains("style='"));
    }

    @Test
    public void codexHandlerConfigJelly_exists() {
        assertNotNull(
                "codex handler config resource should exist",
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/CodexAgentHandler/config.jelly"));
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
