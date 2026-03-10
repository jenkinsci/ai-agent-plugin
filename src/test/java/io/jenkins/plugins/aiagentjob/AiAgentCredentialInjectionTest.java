package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.util.Secret;

import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class AiAgentCredentialInjectionTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    private FreeStyleProject newProject(
            String name, java.util.function.Consumer<AiAgentBuilder> cfg) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(name);
        AiAgentBuilder builder = new AiAgentBuilder();
        cfg.accept(builder);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    @Test
    public void injectsApiKeyFromCredential() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        // Store a secret text credential
        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "test-api-key",
                        "Test API key",
                        Secret.fromString("sk-test-secret-12345"));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        // Create project that echoes back the env var to prove injection
        FreeStyleProject project =
                newProject(
                        "credential-inject-test",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("test");
                            b.setApiCredentialsId("test-api-key");
                            // Command override that outputs the env var value as JSON so it appears
                            // in the raw log
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"system\\\",\\\"key_set\\\":\\\"$(test -n \\\"$ANTHROPIC_API_KEY\\\" && echo yes || echo no)\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        // The raw log should show key_set=yes because we injected the credential
        String rawLog = Files.readString(action.getRawLogFile().toPath(), StandardCharsets.UTF_8);
        assertTrue("Env var should have been set", rawLog.contains("\"key_set\":\"yes\""));

        // Build log should mention the injection
        String buildLog = build.getLog();
        assertTrue(
                "Build log should mention API key injection",
                buildLog.contains("API key injected as ANTHROPIC_API_KEY"));
    }

    @Test
    public void usesCustomEnvVarOverride() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        StringCredentialsImpl cred =
                new StringCredentialsImpl(
                        CredentialsScope.GLOBAL,
                        "custom-key",
                        "Custom key",
                        Secret.fromString("my-custom-secret"));
        CredentialsProvider.lookupStores(jenkins.getInstance())
                .iterator()
                .next()
                .addCredentials(Domain.global(), cred);

        FreeStyleProject project =
                newProject(
                        "custom-envvar-test",
                        b -> {
                            b.setAgent(new OpenCodeAgentHandler());
                            b.setPrompt("test");
                            b.setApiCredentialsId("custom-key");
                            b.setApiKeyEnvVar("ANTHROPIC_API_KEY");
                            b.setCommandOverride(
                                    "echo \"{\\\"type\\\":\\\"system\\\",\\\"key_set\\\":\\\"$(test -n \\\"$ANTHROPIC_API_KEY\\\" && echo yes || echo no)\\\"}\"");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertTrue(
                "Should inject as ANTHROPIC_API_KEY not OPENAI_API_KEY",
                buildLog.contains("API key injected as ANTHROPIC_API_KEY"));
    }

    @Test
    public void warnsWhenCredentialNotFound() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "missing-cred-test",
                        b -> {
                            b.setAgent(new GeminiCliAgentHandler());
                            b.setPrompt("test");
                            b.setApiCredentialsId("nonexistent-credential-id");
                            b.setCommandOverride("echo '{\"type\":\"system\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertTrue(
                "Build log should warn about missing credential",
                buildLog.contains("WARNING") && buildLog.contains("not found"));
    }

    @Test
    public void noCredentialNoInjection() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "no-cred-test",
                        b -> {
                            b.setAgent(new ClaudeCodeAgentHandler());
                            b.setPrompt("test");
                            // No credential configured
                            b.setCommandOverride("echo '{\"type\":\"system\"}'");
                            b.setFailOnAgentError(true);
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        String buildLog = build.getLog();
        assertFalse("Should not mention API key injection", buildLog.contains("API key injected"));
    }

    @Test
    public void effectiveApiKeyEnvVar_defaultsToAgentType() {
        AiAgentBuilder project = new AiAgentBuilder();
        project.setAgent(new ClaudeCodeAgentHandler());
        project.setApiKeyEnvVar("");
        assertEquals("ANTHROPIC_API_KEY", project.getEffectiveApiKeyEnvVar());

        project.setAgent(new GeminiCliAgentHandler());
        assertEquals("GEMINI_API_KEY", project.getEffectiveApiKeyEnvVar());
    }

    @Test
    public void effectiveApiKeyEnvVar_respectsCustomOverride() {
        AiAgentBuilder project = new AiAgentBuilder();
        project.setAgent(new OpenCodeAgentHandler());
        project.setApiKeyEnvVar("ANTHROPIC_API_KEY");
        assertEquals("ANTHROPIC_API_KEY", project.getEffectiveApiKeyEnvVar());
    }
}
