package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;

import io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@WithJenkins
class AiAgentBuilderConfigRoundTripTest {

    @Test
    void preservesConfiguredFields(JenkinsRule jenkins) throws Exception {
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

        project = jenkins.configRoundtrip(project);

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
    void commandOverride_normalizesWrappedLineBreaks() {
        AiAgentBuilder builder = new AiAgentBuilder();
        builder.setCommandOverride("/opt/opencode\n  --model gpt-5\n  --json");
        assertEquals("/opt/opencode --model gpt-5 --json", builder.getCommandOverride());
    }

    @Test
    void configJelly_usesDescriptorSelectorWithoutInlineScripts() throws Exception {
        String jelly = readResource("/io/jenkins/plugins/aiagentjob/AiAgentBuilder/config.jelly");
        assertTrue(
                jelly.contains(
                        "<f:dropdownDescriptorSelector title=\"Agent Type\" field=\"agent\" descriptors=\"${descriptor.agentDescriptors}\" />"),
                "config.jelly should use descriptor selector");
        assertTrue(jelly.contains("<f:textbox />"), "command override should use textbox");
        assertFalse(
                jelly.contains("<f:expandableTextbox"),
                "command override should not use expandableTextbox");
        assertFalse(jelly.contains("<style"), "config.jelly should not contain inline style tags");
        assertFalse(
                jelly.contains("<script"), "config.jelly should not contain inline script tags");
        assertFalse(
                jelly.contains(" style=")
                        || jelly.contains("style=\"")
                        || jelly.contains("style='"),
                "config.jelly should not contain inline style attributes");
    }

    @Test
    void codexHandlerConfigJelly_exists() {
        assertNotNull(
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/codex/CodexAgentHandler/config.jelly"),
                "codex handler config resource should exist");
    }

    private String readResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "Resource should exist: " + path);
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }
}
