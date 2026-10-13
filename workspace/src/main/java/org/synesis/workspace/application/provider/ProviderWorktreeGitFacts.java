package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.lifecycle.process.GitRepositoryLayout;


/**
 * Reads Git and filesystem facts about a binding's worktree: whether Git registers it on the
 * expected branch and common directory, whether its base commit is an ancestor of HEAD, and
 * whether it holds user changes. The authority checks that build on these facts live elsewhere;
 * keeping the facts here means each one is answered the same way for allocation, refresh, and
 * verification. A fact that cannot be read is reported as "no" or as an exception, never guessed,
 * so callers treat it as unverified.
 */
final class ProviderWorktreeGitFacts {

    private ProviderWorktreeGitFacts() {
    }

    static Binding verifyBinding(Path controlRoot, Binding binding) {
        try {
            Path assigned = Path.of(binding.worktreePath());
            String common = canonicalGitCommon(controlRoot);
            boolean verified = registeredWorktree(controlRoot, assigned, binding) && sameGitCommonDirectory(controlRoot, assigned, binding) && onBranchFromBase(assigned, binding);
            String trust = verified && "VERIFIED".equals(binding.providerTrustState()) ? "VERIFIED" : "WORKSPACE_UNVERIFIED";
            return binding.withWorktreeVerification(controlRoot.toString(), common, verified, trust);
        } catch (Exception failure) {
            return binding.withWorktreeVerification(controlRoot.toString(), binding.gitCommonDir(), false, "WORKSPACE_UNVERIFIED");
        }
    }

    static boolean registeredWorktree(Path root, Path assigned, Binding binding) throws Exception {
        String list = runGit(root, "worktree", "list", "--porcelain");
        boolean pathFound = false;
        for (String line : list.lines().toList()) {
            if (line.startsWith("worktree ")) {
                Path wt = Path.of(line.substring("worktree ".length())).toAbsolutePath().normalize();
                // Git and a packaged Windows host can spell one existing worktree
                // through different LOCALAPPDATA aliases. Files.isSameFile follows
                // those aliases without accepting a merely similar or nonexistent
                // path.
                pathFound = sameFilesystemObject(wt, assigned);
            }
            if (pathFound && line.equals("branch refs/heads/" + binding.branch())) {
                return true;
            }
        }
        return false;
    }

    static boolean sameFilesystemObject(Path left, Path right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return Files.isSameFile(left, right);
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    static boolean sameOrDescendant(Path candidate, Path parent) {
        if (candidate == null || parent == null) {
            return false;
        }
        Path current = candidate.toAbsolutePath().normalize();
        while (current != null) {
            if (sameFilesystemObject(current, parent)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    static boolean sameGitCommonDirectory(Path root, Path assigned, Binding binding) throws Exception {
        Path expected = Path.of(canonicalGitCommon(root));
        Path actual = Path.of(canonicalGitCommon(assigned));
        return sameFilesystemObject(expected, actual) && (binding.gitCommonDir() == null || sameFilesystemObject(Path.of(binding.gitCommonDir()), actual));
    }

    private static String canonicalGitCommon(Path root) throws Exception {
        java.util.Optional<Path> onDisk = GitRepositoryLayout.commonDirectory(root);
        if (onDisk.isPresent()) {
            return onDisk.get().toRealPath().toString();
        }
        Path common = Path.of(git(root, "rev-parse", "--git-common-dir"));
        return (common.isAbsolute() ? common : root.resolve(common)).toRealPath().toString();
    }

    /**
     * The branch a checkout is on and the commit it is at.
     *
     * @param branch short branch name
     * @param commit full commit identifier
     */
    record Head(String branch, String commit) {
    }

    /**
     * Asks a checkout which branch it is on and which commit it is at, in one Git process. Each
     * used to be its own process on every check of a session.
     *
     * @param checkout checkout to ask
     * @return its branch and commit
     * @throws Exception when Git cannot answer, HEAD is detached, or HEAD names no commit
     */
    static Head head(Path checkout) throws Exception {
        // rev-parse takes its arguments in order: the commit is printed first, and only what
        // follows the option is printed as a name.
        List<String> answer = git(checkout, "rev-parse", "HEAD", "--symbolic-full-name", "HEAD").lines().map(String::trim).toList();
        if (answer.size() != 2 || !validCommit(answer.get(0)) || !answer.get(1).startsWith("refs/heads/")) {
            throw new IOException("WORKSPACE_HEAD_UNAVAILABLE");
        }
        return new Head(answer.get(1).substring("refs/heads/".length()), answer.get(0));
    }

    /**
     * Reports whether a checkout is on the binding's branch at a commit that descends from the
     * binding's base commit.
     */
    static boolean onBranchFromBase(Path assigned, Binding binding) throws Exception {
        Head head = head(assigned);
        return binding.branch().equals(head.branch()) && isBaseAncestor(assigned, binding.baseCommit(), head.commit());
    }

    /**
     * Reports whether a base commit is an ancestor of the commit a checkout is at. A commit is
     * its own ancestor, which is the usual case before an agent has committed anything, and Git
     * is not started to hear it.
     */
    static boolean isBaseAncestor(Path assigned, String baseCommit, String headCommit) throws Exception {
        if (!validCommit(baseCommit)) {
            return false;
        }
        return baseCommit.equals(headCommit) || GitProcessRunner.isAncestor(assigned, baseCommit, "HEAD");
    }

    static boolean validCommit(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}") && !value.matches("0{40}");
    }

    static String currentCommit(Path root) {
        try {
            String value = git(root, "rev-parse", "--verify", "HEAD");
            return validCommit(value) ? value : "GIT_HEAD_UNAVAILABLE";
        } catch (Exception ignored) {
            return "GIT_HEAD_UNAVAILABLE";
        }
    }

    static String git(Path root, String... arguments) throws Exception {
        return runGit(root, arguments).trim();
    }

    static String runGit(Path root, String... arguments) throws Exception {
        return GitProcessRunner.run(root, arguments);
    }

    static List<String> gitPaths(Path root, String... arguments) throws IOException {
        byte[] output = GitProcessRunner.runBytes(root, arguments);
        if (output.length >= GitProcessRunner.DEFAULT_MAX_OUTPUT_BYTES) {
            throw new IOException("Workspace path inspection exceeded its output bound");
        }
        return java.util.Arrays.stream(new String(output, StandardCharsets.UTF_8).split("\u0000")).filter(path -> !path.isEmpty()).toList();
    }

    static boolean isWorktreeClean(Binding binding) {
        if (binding == null || binding.worktreePath() == null) {
            return false;
        }
        try {
            String status = git(Path.of(binding.worktreePath()), "status", "--porcelain", "--untracked-files=all");
            return status.lines().map(String::trim).filter(line -> !line.isBlank()).allMatch(ProviderWorktreeGitFacts::isSafeWorkspaceStatusLine);
        } catch (Exception failure) {
            return false;
        }
    }

    private static boolean isSafeWorkspaceStatusLine(String line) {
        String path = line.length() > 3 ? line.substring(3).trim() : line.trim();
        String normalized = path.replace('\\', '/');
        return line.endsWith(".synesis") || line.contains(".synesis/") || line.contains(".claude/settings.json") || line.contains(".codex/hooks.json") || isPythonBytecodeCache(normalized);
    }

    private static boolean isPythonBytecodeCache(String path) {
        return path.equals("__pycache__") || path.startsWith("__pycache__/") || path.endsWith("/__pycache__") || path.contains("/__pycache__/");
    }

    static boolean hasConfirmedUncommittedWork(Binding binding) {
        if (binding == null || binding.worktreePath() == null) {
            return false;
        }
        try {
            String status = git(Path.of(binding.worktreePath()), "status", "--porcelain", "--untracked-files=all");
            return status.lines().map(String::trim).filter(line -> !line.isBlank()).anyMatch(line -> !isSafeWorkspaceStatusLine(line));
        } catch (Exception failure) {
            return false;
        }
    }
}
