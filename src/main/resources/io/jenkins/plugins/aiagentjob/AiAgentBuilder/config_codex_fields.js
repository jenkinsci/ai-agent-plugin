(function () {
    function findFieldBySuffix(suffix) {
        return document.querySelector(
            "[name='" + suffix + "'], [name$='." + suffix + "'], [name$='" + suffix + "']"
        );
    }

    function setSectionVisible(selector, visible) {
        var sections = document.querySelectorAll(selector);
        for (var i = 0; i < sections.length; i++) {
            sections[i].hidden = !visible;
            var fields = sections[i].querySelectorAll("input, select, textarea, button");
            for (var j = 0; j < fields.length; j++) {
                fields[j].disabled = !visible;
            }
        }
    }

    function updateConditionalFields() {
        var agentType = findFieldBySuffix("agentType");
        if (agentType) {
            setSectionVisible(
                "[data-ai-agent-codex-only='true']",
                agentType.value === "CODEX"
            );
        }
    }

    function bindField(fieldSuffix, eventName) {
        var field = findFieldBySuffix(fieldSuffix);
        if (!field) {
            return false;
        }
        var boundKey = "aiAgentToggleBound" + fieldSuffix;
        if (field.dataset[boundKey] === "true") {
            return true;
        }
        field.dataset[boundKey] = "true";
        field.addEventListener(eventName, updateConditionalFields);
        return true;
    }

    function bindConditionalToggles() {
        bindField("agentType", "change");
        updateConditionalFields();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", bindConditionalToggles);
    } else {
        bindConditionalToggles();
    }

    if (window.MutationObserver) {
        var observer = new MutationObserver(bindConditionalToggles);
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }
})();
