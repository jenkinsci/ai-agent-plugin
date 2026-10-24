package io.jenkins.plugins.aiagentjob;

import edu.umd.cs.findbugs.annotations.CheckForNull;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;

import jenkins.MasterToSlaveFileCallable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Copies the raw agent log captured on the controller to a workspace-relative file on the build
 * node, so external systems can collect agent conversations with ordinary workspace tooling.
 *
 * <p>Any existing entry at the target path is removed before the agent starts, and the copy is made
 * after the agent process exits. The agent therefore cannot read a previous export at that path or
 * read or rewrite the new one while it runs, and the exported content is identical to the
 * build-page raw log, including credential masking.
 */
final class AiAgentRawLogExport {
    private static final Pattern SEPARATORS = Pattern.compile("[/\\\\]");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private AiAgentRawLogExport() {}

    /**
     * Returns the validation problem for a configured path, or {@code null} when the path is empty
     * or acceptable. The check is lexical; symlinks are resolved on the build node during export.
     */
    @CheckForNull
    static String validationError(@CheckForNull String path) {
        String trimmed = Util.fixNull(path).trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("/")
                || trimmed.startsWith("\\")
                || WINDOWS_DRIVE.matcher(trimmed).matches()) {
            return "Raw log path must be relative to the workspace: " + trimmed;
        }
        if (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            return "Raw log path must name a file, not a directory: " + trimmed;
        }
        String[] segments = SEPARATORS.split(trimmed);
        for (String segment : segments) {
            if ("..".equals(segment)) {
                return "Raw log path must stay inside the workspace and cannot contain '..': "
                        + trimmed;
            }
        }
        String fileName = segments[segments.length - 1];
        if (fileName.isEmpty() || ".".equals(fileName)) {
            return "Raw log path must name a file, not a directory: " + trimmed;
        }
        return null;
    }

    /**
     * Validates a configured path before the agent starts and normalizes it: both {@code /} and
     * {@code \} are separators, and empty and {@code .} segments are dropped.
     *
     * @return the normalized {@code /}-separated path, or {@code null} when export is disabled
     */
    @CheckForNull
    static String resolve(@CheckForNull String path) throws AbortException {
        String error = validationError(path);
        if (error != null) {
            throw new AbortException("[ai-agent] " + error);
        }
        String trimmed = Util.fixEmptyAndTrim(path);
        if (trimmed == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String segment : SEPARATORS.split(trimmed)) {
            if (!segment.isEmpty() && !".".equals(segment)) {
                kept.add(segment);
            }
        }
        return String.join("/", kept);
    }

    /**
     * Removes any existing entry at the target path before the agent starts, so the agent cannot
     * read a previous export left in a reused workspace.
     */
    static void clearBeforeLaunch(FilePath workspace, String relativePath, TaskListener listener)
            throws IOException, InterruptedException {
        checkParentInsideWorkspace(workspace, relativePath);
        if (workspace.act(new RemoveTarget(relativePath)) != null) {
            listener.getLogger()
                    .println(
                            "[ai-agent] Removed existing file at raw log path before starting the"
                                    + " agent: "
                                    + relativePath);
        }
    }

    /**
     * Copies {@code source} to {@code relativePath} inside {@code workspace}. Any non-directory
     * entry at the target (a file, a symlink, or a FIFO) is removed first, and the new file is
     * created exclusively, so a symlink or special file at the target is never followed. A parent
     * directory that resolves outside the workspace is rejected.
     */
    static void export(File source, FilePath workspace, String relativePath, TaskListener listener)
            throws IOException, InterruptedException {
        if (!source.isFile()) {
            listener.getLogger()
                    .println(
                            "[ai-agent] No raw agent log was captured; nothing to save to "
                                    + relativePath);
            return;
        }

        String parentPath = checkParentInsideWorkspace(workspace, relativePath);
        if (!parentPath.isEmpty()) {
            workspace.child(parentPath).mkdirs();
        }
        if (RemoveTarget.SYMLINK.equals(workspace.act(new RemoveTarget(relativePath)))) {
            listener.getLogger()
                    .println(
                            "[ai-agent] Replacing symlink at raw log path with a regular file: "
                                    + relativePath);
        }

        try (OutputStream out = workspace.act(new CreateNewFile(relativePath))) {
            new FilePath(source).copyTo(out);
        }
        listener.getLogger()
                .println("[ai-agent] Raw agent log saved to workspace path: " + relativePath);
    }

    private static String checkParentInsideWorkspace(FilePath workspace, String relativePath)
            throws IOException, InterruptedException {
        String parentPath = parentOf(relativePath);
        if (!parentPath.isEmpty() && !workspace.isDescendant(parentPath)) {
            throw new AbortException(
                    "[ai-agent] Raw log path parent resolves outside the workspace (check for"
                            + " symlinks): "
                            + relativePath);
        }
        return parentPath;
    }

    private static String parentOf(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash <= 0 ? "" : relativePath.substring(0, slash);
    }

    /** Removes a non-directory entry at the path without following a symlink there. */
    private static final class RemoveTarget extends MasterToSlaveFileCallable<String> {
        private static final long serialVersionUID = 1L;
        static final String SYMLINK = "symlink";
        static final String FILE = "file";

        private final String relativePath;

        RemoveTarget(String relativePath) {
            this.relativePath = relativePath;
        }

        @Override
        public String invoke(File workspace, VirtualChannel channel) throws IOException {
            Path target = workspace.toPath().resolve(relativePath);
            if (Files.isSymbolicLink(target)) {
                Files.delete(target);
                return SYMLINK;
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new AbortException(
                        "[ai-agent] Raw log path points to an existing directory: " + relativePath);
            }
            return Files.deleteIfExists(target) ? FILE : null;
        }
    }

    /** Opens a new file for writing and fails if any entry appeared at the path meanwhile. */
    private static final class CreateNewFile extends MasterToSlaveFileCallable<OutputStream> {
        private static final long serialVersionUID = 1L;

        private final String relativePath;

        CreateNewFile(String relativePath) {
            this.relativePath = relativePath;
        }

        @Override
        public OutputStream invoke(File workspace, VirtualChannel channel) throws IOException {
            Path target = workspace.toPath().resolve(relativePath);
            return new RemoteOutputStream(
                    Files.newOutputStream(
                            target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
        }
    }
}
