package io.jenkins.plugins.aiagentjob;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

class StaplerEndpointSecurityTest {
    @Test
    void descriptorEndpoints_usePostVerbs() throws Exception {
        assertTrue(
                AiAgentBuilder.DescriptorImpl.class
                        .getMethod(
                                "doFillApiCredentialsIdItems",
                                hudson.model.Item.class,
                                String.class)
                        .isAnnotationPresent(POST.class));
        assertTrue(
                AiAgentBuilder.DescriptorImpl.class
                        .getMethod(
                                "doCheckApprovalTimeoutSeconds",
                                hudson.model.Item.class,
                                String.class,
                                String.class)
                        .isAnnotationPresent(POST.class));
    }

    @Test
    void runActionEndpoints_useExpectedHttpVerbs() throws Exception {
        assertTrue(
                AiAgentRunAction.class
                        .getMethod(
                                "doProgressiveEvents",
                                StaplerRequest2.class,
                                StaplerResponse2.class)
                        .isAnnotationPresent(GET.class));
        assertTrue(AiAgentRunAction.class.getMethod("doIndex").isAnnotationPresent(GET.class));
        assertTrue(
                AiAgentRunAction.class
                        .getMethod("doApprove", String.class)
                        .isAnnotationPresent(RequirePOST.class));
        assertTrue(
                AiAgentRunAction.class
                        .getMethod("doDeny", String.class, String.class)
                        .isAnnotationPresent(RequirePOST.class));
    }

    @Test
    void builderDescriptor_isApplicableToAnyProject() {
        assertTrue(
                new AiAgentBuilder.DescriptorImpl()
                        .isApplicable(hudson.model.FreeStyleProject.class));
    }
}
