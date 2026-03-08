package io.jenkins.plugins.aiagentjob;

import static org.junit.Assert.assertEquals;

import hudson.model.FreeStyleProject;
import hudson.util.FormValidation;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class AiAgentBuilderDescriptorValidationTest {
    @Rule public JenkinsRule jenkins = new JenkinsRule();

    @Test
    public void timeout_isOptionalWhenApprovalsDisabled() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("ai-validation-approvals-off");
        AiAgentBuilder.DescriptorImpl descriptor = new AiAgentBuilder.DescriptorImpl();

        FormValidation result = descriptor.doCheckApprovalTimeoutSeconds(project, "", "false");

        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    public void timeout_isRequiredWhenApprovalsEnabled() throws Exception {
        FreeStyleProject project = jenkins.createFreeStyleProject("ai-validation-approvals-on");
        AiAgentBuilder.DescriptorImpl descriptor = new AiAgentBuilder.DescriptorImpl();

        FormValidation result = descriptor.doCheckApprovalTimeoutSeconds(project, "", "true");

        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }
}
