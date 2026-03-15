package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.security.csrf.DefaultCrumbIssuer;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.htmlunit.html.HtmlPage;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/** Tests for {@link AiAgentRunAction} metadata, getters, and progressive event API. */
public class AiAgentRunActionTest {
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
    public void getOrCreate_returnsSameInstance() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "test-getorcreate",
                        b -> b.setCommandOverride("echo '{\"type\":\"system\"}'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction first = build.getAction(AiAgentRunAction.class);
        AiAgentRunAction second = AiAgentRunAction.getOrCreate(build);
        assertNotNull(first);
        assertEquals("getOrCreate should return existing action", first, second);
    }

    @Test
    public void actionProperties_afterCompletion() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
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
        assertNull("No sidebar icon", action.getIconFileName());
    }

    @Test
    public void pendingApprovals_emptyAfterCompletion() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "test-approvals",
                        b -> b.setCommandOverride("echo '{\"type\":\"system\"}'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);
        assertTrue(action.getPendingApprovals().isEmpty());
    }

    @Test
    public void progressiveEvents_returnsJsonWithEvents() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        String script = buildEchoScript("claude-code-conversation.jsonl");
        FreeStyleProject project =
                newProject("test-progressive", b -> b.setCommandOverride(script));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        String url = build.getUrl() + "ai-agent/progressiveEvents?start=0";
        org.htmlunit.Page page = wc.goTo(url, "application/json");
        String json = page.getWebResponse().getContentAsString();

        JSONObject result = JSONObject.fromObject(json);
        assertTrue("Should have events array", result.has("events"));
        assertTrue("Should have nextStart", result.has("nextStart"));
        assertTrue("Should have live field", result.has("live"));

        JSONArray events = result.getJSONArray("events");
        assertTrue("Should have at least one event", events.size() > 0);

        long nextStart = result.getLong("nextStart");
        assertTrue("nextStart should be > 0", nextStart > 0);
        assertFalse("Build should not be live", result.getBoolean("live"));
    }

    @Test
    public void progressiveEvents_incrementalFetch() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        String script = buildEchoScript("claude-code-conversation.jsonl");
        FreeStyleProject project =
                newProject("test-incremental", b -> b.setCommandOverride(script));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);

        JenkinsRule.WebClient wc = jenkins.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        String url1 = build.getUrl() + "ai-agent/progressiveEvents?start=0";
        String json1 = wc.goTo(url1, "application/json").getWebResponse().getContentAsString();
        JSONObject result1 = JSONObject.fromObject(json1);
        long nextStart = result1.getLong("nextStart");
        int firstBatch = result1.getJSONArray("events").size();
        assertTrue("First batch should have events", firstBatch > 0);

        String url2 = build.getUrl() + "ai-agent/progressiveEvents?start=" + nextStart;
        String json2 = wc.goTo(url2, "application/json").getWebResponse().getContentAsString();
        JSONObject result2 = JSONObject.fromObject(json2);
        assertEquals(
                "Second fetch should return no new events",
                0,
                result2.getJSONArray("events").size());
        assertEquals("nextStart should be unchanged", nextStart, result2.getLong("nextStart"));
    }

    @Test
    public void rawEndpoint_rendersHtmlWithContent() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
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
        assertTrue("Raw page should render preformatted content", content.contains("<pre"));
        assertTrue("Raw log should contain the event type", content.contains("type"));
        assertTrue("Raw log should contain the event category", content.contains("system"));
    }

    @Test
    public void events_emptyWhenLogContainsOnlyHiddenBookkeeping() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
                        "test-no-log", b -> b.setCommandOverride("echo '{\"type\":\"result\"}'"));

        FreeStyleBuild build = jenkins.buildAndAssertSuccess(project);
        AiAgentRunAction action = build.getAction(AiAgentRunAction.class);
        assertNotNull(action);

        File rawLog = action.getRawLogFile();
        assertTrue("Raw log should exist", rawLog.exists());

        List<AiAgentLogParser.EventView> events = action.getEvents();
        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    @Test
    public void summaryJelly_usesExternalResourcesForCspCompliance() throws Exception {
        String jelly =
                readResource("/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary.jelly");
        assertTrue(
                "summary.jelly should load marked for markdown rendering",
                jelly.contains(
                        "<st:adjunct includes=\"io.jenkins.plugins.aiagentjob.AiAgentRunAction.marked\""));
        assertTrue(
                "summary.jelly should load adjunct resources",
                jelly.contains(
                        "<st:adjunct includes=\"io.jenkins.plugins.aiagentjob.AiAgentRunAction.summary_resources\""));
        assertFalse("summary.jelly should not contain inline style tags", jelly.contains("<style"));
        assertFalse(
                "summary.jelly should not contain inline script tags", jelly.contains("<script"));
        assertFalse(
                "summary.jelly should not contain inline style attributes",
                jelly.contains(" style=")
                        || jelly.contains("style=\"")
                        || jelly.contains("style='"));
    }

    @Test
    public void summaryResources_exist() throws Exception {
        assertNotNull(
                "summary CSS resource should exist",
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary_resources.css"));
        assertNotNull(
                "summary JS resource should exist",
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary_resources.js"));
        assertNotNull(
                "marked JS resource should exist",
                getClass()
                        .getResource("/io/jenkins/plugins/aiagentjob/AiAgentRunAction/marked.js"));
        assertNotNull(
                "conversation jelly resource should exist",
                getClass()
                        .getResource(
                                "/io/jenkins/plugins/aiagentjob/AiAgentRunAction/conversation.jelly"));
    }

    @Test
    public void progressiveEvents_returnsCrumbWhenIssuerConfigured() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        jenkins.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(true));

        FreeStyleProject project =
                newProject(
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
                "Crumb request field should be exposed to the live view",
                jenkins.jenkins.getCrumbIssuer().getCrumbRequestField(),
                json.getString("crumbRequestField"));
        assertTrue("Crumb value should be included", json.containsKey("crumb"));
        assertFalse("Crumb value should not be empty", json.getString("crumb").isEmpty());
    }

    @Test
    public void conversationPage_showsPromptAndFullConversationLinkTarget() throws Exception {
        Assume.assumeTrue(File.pathSeparatorChar == ':');

        FreeStyleProject project =
                newProject(
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
        assertTrue(text.contains("Raw stream-json log"));
    }

    private String buildEchoScript(String fixtureName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("fixtures/" + fixtureName);
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> lines = reader.lines().collect(Collectors.toList());
            StringBuilder script = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                String escaped = lines.get(i).replace("\\", "\\\\").replace("'", "'\\''");
                if (i > 0) {
                    script.append(" && ");
                }
                script.append("echo '").append(escaped).append("'");
            }
            return script.toString();
        }
    }

    private String readResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull("Resource should exist: " + resourcePath, is);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
