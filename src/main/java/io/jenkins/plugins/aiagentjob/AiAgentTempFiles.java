package io.jenkins.plugins.aiagentjob;

import hudson.FilePath;
import hudson.slaves.WorkspaceList;

import java.io.IOException;

public final class AiAgentTempFiles {
    private AiAgentTempFiles() {}

    public static FilePath tempRoot(FilePath workspace) throws IOException, InterruptedException {
        FilePath tempRoot = WorkspaceList.tempDir(workspace);
        if (tempRoot == null) {
            FilePath parent = workspace.getParent();
            tempRoot =
                    parent != null
                            ? parent.child(workspace.getName() + "@tmp")
                            : workspace.child(".ai-agent-tmp");
        }
        tempRoot.mkdirs();
        return tempRoot;
    }
}
