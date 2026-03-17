package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.security.csrf.DefaultCrumbIssuer;

import io.jenkins.plugins.aiagentjob.geminicli.GeminiCliAgentHandler;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Tests for {@link AiAgentRunAction} metadata, getters, and progressive event API. */
@WithJenkins
class AiAgentRunActionTest {

    private FreeStyleProject newProject(
            JenkinsRule jenkins, String name, java.util.function.Consumer<AiAgentBuilder> cfg)
            throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject(name);
        AiAgentBuilder builder = new AiAgentBuilder();
        cfg.accept(builder);
        project.getBuildersList().add(builder);
        project.save();
        return project;
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void getOrCreate_returnsSameInstance(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "test-getorcreate",
                        b -> b.setCommandOverride("echo '{\"type\":\"system\"}'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction first = build.getAction(AiAgentRunAction.class);
        AiAgentRunAction second = AiAgentRunAction.getOrCreate(build);
        assertNotNull(first);
        assertEquals(first, second, "getOrCreate should return existing action");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void actionProperties_afterCompletion(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "test-props",
                        b -> {
                            b.setAgent(new GeminiCliAgentHandler());
                            b.setModel("gemini-2.5-pro");
                            b.setYoloMode(true);
                            b.setCommandOverride(
                                    "echo '{\"type\":\"result\",\"result\":\"done\"}'");
                        });

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        assertEquals("Gemini CLI", action.getAgentType());
        assertEquals("gemini-2.5-pro", action.getModel());
        assertTrue(action.isYoloMode());
        assertFalse(action.isApprovalsEnabled());
        assertEquals(Integer.valueOf(0), action.getExitCode());
        assertNotNull(action.getStartedAt());
        assertFalse(action.getStartedAt().isEmpty());
        assertNotNull(action.getCompletedAt());
        assertNotNull(action.getCommandLine());
        assertFalse(action.isLive());
        assertEquals("AI Agent Conversation", action.getDisplayName());
        assertEquals("ai-agent", action.getUrlName());
        assertNull(action.getIconFileName(), "No sidebar icon");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void pendingApprovals_emptyAfterCompletion(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "test-approvals",
                        b -> b.setCommandOverride("echo '{\"type\":\"system\"}'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getPendingApprovals().isEmpty());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void progressiveEvents_returnsJsonWithEvents(JenkinsRule jenkins) throws Exception {
        String script = buildCatScript("claude-code-conversation.jsonl");
        FreeStyleProject project =
                newProject(jenkins, "test-progressive", b -> b.setCommandOverride(script));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        String url = build.getUrl() + "ai-agent/progressiveEvents?start=0";
        org.htmlunit.Page page = wc.goTo(url, "application/json");
        String json = page.getWebResponse().getContentAsString();

        JSONObject result = JSONObject.fromObject(json);
        assertTrue(result.has("events"), "Should have events array");
        assertTrue(result.has("nextStart"), "Should have nextStart");
        assertTrue(result.has("live"), "Should have live field");

        JSONArray events = result.getJSONArray("events");
        assertTrue(events.size() > 0, "Should have at least one event");

        long nextStart = result.getLong("nextStart");
        assertTrue(nextStart > 0, "nextStart should be > 0");
        assertFalse(result.getBoolean("live"), "Build should not be live");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void progressiveEvents_incrementalFetch(JenkinsRule jenkins) throws Exception {
        String script = buildCatScript("claude-code-conversation.jsonl");
        FreeStyleProject project =
                newProject(jenkins, "test-incremental", b -> b.setCommandOverride(script));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        String url1 = build.getUrl() + "ai-agent/progressiveEvents?start=0";
        String json1 = wc.goTo(url1, "application/json").getWebResponse().getContentAsString();
        JSONObject result1 = JSONObject.fromObject(json1);
        long nextStart = result1.getLong("nextStart");
        int firstBatch = result1.getJSONArray("events").size();
        assertTrue(firstBatch > 0, "First batch should have events");

        String url2 = build.getUrl() + "ai-agent/progressiveEvents?start=" + nextStart;
        String json2 = wc.goTo(url2, "application/json").getWebResponse().getContentAsString();
        JSONObject result2 = JSONObject.fromObject(json2);
        assertEquals(
                0,
                result2.getJSONArray("events").size(),
                "Second fetch should return no new events");
        assertEquals(nextStart, result2.getLong("nextStart"), "nextStart should be unchanged");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rawEndpoint_rendersHtmlWithContent(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "test-raw",
                        b ->
                                b.setCommandOverride(
                                        "echo '{\"type\":\"system\",\"subtype\":\"init\"}'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        String url = build.getUrl() + "ai-agent/raw";
        org.htmlunit.Page page = wc.goTo(url);
        String content = page.getWebResponse().getContentAsString();
        assertTrue(content.contains("<pre"), "Raw page should render preformatted content");
        assertTrue(content.contains("type"), "Raw log should contain the event type");
        assertTrue(content.contains("system"), "Raw log should contain the event category");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void events_emptyWhenLogContainsOnlyHiddenBookkeeping(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "test-no-log",
                        b -> b.setCommandOverride("echo '{\"type\":\"result\"}'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        java.io.File rawLog = action.getRawLogFile();
        assertTrue(rawLog.exists(), "Raw log should exist");

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    void summaryJelly_usesExternalResourcesForCspCompliance() throws Exception {
        String jelly =
                readResource("/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary.jelly");
        assertTrue(
                jelly.contains("plugin/ai-agent/js/marked"),
                "summary.jelly should load marked from plugin webapp for markdown rendering");
        assertTrue(
                jelly.contains(
                        "<st:adjunct includes=\"io.jenkins.plugins.aiagentjob.AiAgentRunAction.summary_resources\""),
                "summary.jelly should load adjunct resources");
        assertFalse(jelly.contains("<style"), "summary.jelly should not contain inline style tags");
        assertFalse(
                jelly.contains(" style=")
                        || jelly.contains("style=\"")
                        || jelly.contains("style='"),
                "summary.jelly should not contain inline style attributes");
    }

    @Test
    void summaryResources_exist() {
        assertNotNull(
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary_resources.css"),
                "summary CSS resource should exist");
        assertNotNull(
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary_resources.js"),
                "summary JS resource should exist");
        assertNotNull(
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/AiAgentRunAction/conversation.jelly"),
                "conversation jelly resource should exist");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void progressiveEvents_returnsCrumbWhenIssuerConfigured(JenkinsRule jenkins) throws Exception {
        jenkins.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(true));

        FreeStyleProject project =
                newProject(
                        jenkins,
                        "test-progressive-crumb",
                        b ->
                                b.setCommandOverride(
                                        "echo '{\"type\":\"assistant\",\"message\":\"ok\"}'"));
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        String url = build.getUrl() + "ai-agent/progressiveEvents?start=0";
        String body = wc.goTo(url, "application/json").getWebResponse().getContentAsString();
        JSONObject json = JSONObject.fromObject(body);

        assertEquals(
                jenkins.jenkins.getCrumbIssuer().getCrumbRequestField(),
                json.getString("crumbRequestField"),
                "Crumb request field should be exposed to the live view");
        assertTrue(json.containsKey("crumb"), "Crumb value should be included");
        assertFalse(json.getString("crumb").isEmpty(), "Crumb value should not be empty");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void conversationPage_showsPromptAndFullConversationLinkTarget(JenkinsRule jenkins)
            throws Exception {
        FreeStyleProject project =
                newProject(
                        jenkins,
                        "test-conversation-page",
                        b -> {
                            b.setPrompt("Explain this repository in detail");
                            b.setCommandOverride(
                                    "echo '{\"type\":\"assistant\",\"message\":\"hello\"}'");
                        });
        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setCssEnabled(false);
        HtmlPage page = wc.goTo(build.getUrl() + "ai-agent/conversation?invocation=1");
        String text = page.asNormalizedText();

        assertTrue(text.contains("AI Agent Conversation #1"));
        assertTrue(text.contains("Explain this repository in detail"));
        assertTrue(text.contains("Raw Log"));
    }

    private String buildCatScript(String fixtureName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("fixtures/" + fixtureName)) {
            byte[] data = is.readAllBytes();
            File temp = File.createTempFile("fixture-", ".jsonl");
            temp.deleteOnExit();
            Files.write(temp.toPath(), data);
            return "cat " + temp.getAbsolutePath();
        }
    }

    private String readResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Resource should exist: " + resourcePath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
