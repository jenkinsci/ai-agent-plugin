package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.FreeStyleProject;
import hudson.util.FormValidation;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AiAgentBuilderDescriptorValidationTest {

    @Test
    void timeout_isOptionalWhenApprovalsDisabled(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("ai-validation-approvals-off");
        AiAgentBuilder.DescriptorImpl descriptor = new AiAgentBuilder.DescriptorImpl();

        FormValidation result = descriptor.doCheckApprovalTimeoutSeconds(project, "", "false");

        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    void timeout_isRequiredWhenApprovalsEnabled(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("ai-validation-approvals-on");
        AiAgentBuilder.DescriptorImpl descriptor = new AiAgentBuilder.DescriptorImpl();

        FormValidation result = descriptor.doCheckApprovalTimeoutSeconds(project, "", "true");

        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    void rawLogPath_acceptsWorkspaceRelativeFile(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("ai-validation-raw-log-ok");
        AiAgentBuilder.DescriptorImpl descriptor = new AiAgentBuilder.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckRawLogPath(project, "").kind);
        assertEquals(
                FormValidation.Kind.OK,
                descriptor.doCheckRawLogPath(project, "ai-logs/run.jsonl").kind);
    }

    @Test
    void rawLogPath_rejectsPathOutsideWorkspace(JenkinsRule jenkins) throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("ai-validation-raw-log-bad");
        AiAgentBuilder.DescriptorImpl descriptor = new AiAgentBuilder.DescriptorImpl();

        assertEquals(
                FormValidation.Kind.ERROR,
                descriptor.doCheckRawLogPath(project, "../run.jsonl").kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                descriptor.doCheckRawLogPath(project, "/var/log/run.jsonl").kind);
    }
}
