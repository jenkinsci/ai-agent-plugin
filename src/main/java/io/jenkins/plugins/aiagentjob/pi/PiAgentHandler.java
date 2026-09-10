package io.jenkins.plugins.aiagentjob.pi;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;

import io.jenkins.plugins.aiagentjob.AiAgentConfiguration;
import io.jenkins.plugins.aiagentjob.AiAgentLogFormat;
import io.jenkins.plugins.aiagentjob.AiAgentStatsExtractor;
import io.jenkins.plugins.aiagentjob.AiAgentTypeHandler;

import net.sf.json.JSONObject;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PiAgentHandler extends AiAgentTypeHandler {
    @DataBoundConstructor
    public PiAgentHandler() {}

    @Override
    public String getId() {
        return "PI";
    }

    @Override
    public String getDefaultApiKeyEnvVar() {
        return "";
    }

    @Override
    protected Set<String> getSupportedReasoningEfforts() {
        return Set.of("off", "minimal", "low", "medium", "high", "xhigh", "max");
    }

    @Override
    public void validateExecution(AiAgentConfiguration config) {
        super.validateExecution(config);
        if (Util.fixEmptyAndTrim(config.getApiCredentialsId()) != null
                && Util.fixEmptyAndTrim(config.getEffectiveApiKeyEnvVar()) == null) {
            throw new IllegalArgumentException(
                    "Pi credentials require an API Key env var override for the selected provider, "
                            + "such as OPENAI_API_KEY or ANTHROPIC_API_KEY.");
        }
    }

    @Override
    public List<String> buildDefaultCommand(AiAgentConfiguration config, String prompt) {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "pi",
                                "--print",
                                "--mode",
                                "json",
                                "--no-session",
                                "--no-extensions"));
        if (config.isYoloMode()) {
            command.add("--approve");
        } else {
            command.add("--no-approve");
            command.add("--tools");
            command.add("read,grep,find,ls");
        }
        ModelSelection selection =
                resolveModelSelection(config.getModel(), config.getReasoningEffort());
        String model = Util.fixEmptyAndTrim(selection.getModel());
        if (model != null) {
            command.add("--model");
            command.add(model);
        }
        String effort = Util.fixEmptyAndTrim(selection.getReasoningEffort());
        if (effort != null) {
            if (!getSupportedReasoningEfforts().contains(effort)) {
                throw new IllegalArgumentException("Unsupported Pi reasoning effort: " + effort);
            }
            command.add("--thinking");
            command.add(effort);
        }
        // Pi has no end-of-options marker and treats @ prefixes as file attachments.
        command.add(prompt.startsWith("-") || prompt.startsWith("@") ? "\n" + prompt : prompt);
        return command;
    }

    @Override
    public AiAgentLogFormat getLogFormat() {
        return PiLogFormat.INSTANCE;
    }

    @Override
    public AiAgentStatsExtractor getStatsExtractor() {
        return PiStatsExtractor.INSTANCE;
    }

    @Override
    public int resolveExitCode(int processExitCode, File rawLogFile) throws IOException {
        if (processExitCode != 0 || rawLogFile == null || !rawLogFile.isFile()) {
            return processExitCode;
        }
        boolean failed = false;
        try (BufferedReader reader =
                Files.newBufferedReader(rawLogFile.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject json;
                try {
                    json = JSONObject.fromObject(line);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (!"message_end".equals(json.optString("type"))) {
                    continue;
                }
                JSONObject message = json.optJSONObject("message");
                if (message != null && "assistant".equals(message.optString("role"))) {
                    String reason = message.optString("stopReason");
                    // A successful retry supersedes the earlier failed assistant attempt.
                    failed = "error".equals(reason) || "aborted".equals(reason);
                }
            }
        }
        return failed ? 1 : 0;
    }

    @Extension
    @Symbol("pi")
    public static final class DescriptorImpl extends Descriptor<AiAgentTypeHandler> {
        @Override
        public String getDisplayName() {
            return "Pi Coding Agent";
        }
    }
}
