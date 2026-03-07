(function () {
    function updateCodexFields() {
        var agentType = document.getElementById("ai-agent-job-agent-type");
        if (!agentType) {
            return;
        }

        var showCodexFields = agentType.value === "CODEX";
        var codexOnly = document.querySelectorAll("[data-ai-agent-codex-only='true']");
        for (var i = 0; i < codexOnly.length; i++) {
            codexOnly[i].hidden = !showCodexFields;
        }
    }

    function bindCodexFieldToggle() {
        var agentType = document.getElementById("ai-agent-job-agent-type");
        if (!agentType || agentType.dataset.aiAgentCodexToggleBound === "true") {
            updateCodexFields();
            return;
        }

        agentType.dataset.aiAgentCodexToggleBound = "true";
        agentType.addEventListener("change", updateCodexFields);
        updateCodexFields();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bindCodexFieldToggle);
    } else {
        bindCodexFieldToggle();
    }
})();
