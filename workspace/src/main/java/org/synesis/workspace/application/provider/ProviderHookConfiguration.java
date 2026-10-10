package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;

import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.atomicWrite;
import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.list;
import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.object;
import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.readObject;

/**
 * Writes and inspects the hook entries Synesis keeps in a provider's hook configuration file. The
 * ownership decisions live here: which entries Synesis considers its own and replaces, and which
 * belong to the user and make the write fail with a configuration conflict. The Codex path takes a
 * per-file lock and re-checks the file immediately before writing, because that file sits in the
 * user's worktree. It is separate from {@link ProviderApplicationService} so the rules for touching
 * a file the user also edits are read and changed in one place.
 */
final class ProviderHookConfiguration {

    private static final Map<Path, Object> HOOK_LOCKS = new ConcurrentHashMap<>();

    private ProviderHookConfiguration() {
    }

    static List<Object> managedEntries(Map<String, Object> root, ProviderIntegration provider) {
        Map<String, Object> group = object(root.get(provider.hookGroup()));
        return group == null ? List.of() : list(group.get("PreToolUse")).stream().filter(provider::isManagedHook).toList();
    }

    static boolean managedCommandMatches(Map<String, Object> root, ProviderIntegration provider, Path launcher, Path profile) {
        List<Object> entries = managedEntries(root, provider);
        if (entries.size() != 1) {
            return false;
        }
        Map<String, Object> hook = object(entries.getFirst());
        if (hook == null || !provider.matcher().equals(hook.get("matcher"))) {
            return false;
        }
        List<Object> commands = list(hook.get("hooks"));
        if (commands.size() != 1) {
            return false;
        }
        Map<String, Object> command = object(commands.getFirst());
        if (command == null || !provider.hookCommand(launcher, profile).equals(command.get("command"))) {
            return false;
        }
        String windowsCommand = provider.windowsHookCommand(launcher, profile);
        Object configuredWindows = command.containsKey("commandWindows") ? command.get("commandWindows") : command.get("command_windows");
        if (configuredWindows == null) {
            configuredWindows = command.get("commandWindows");
        }
        return windowsCommand == null || windowsCommand.equals(configuredWindows);
    }

    /**
     * What a merge does with a {@code SessionStart} list that ends up empty. The control project's
     * file drops the key; the copy written into a lane worktree has always kept the empty array.
     */
    enum EmptySessionStart {
        KEEP,
        DROP
    }

    /**
     * Replaces the provider's managed entries in the hook group of {@code root}, leaving every other
     * entry in place.
     *
     * @return whether the group already held exactly the current managed pre-tool entry
     */
    static boolean mergeManagedHooks(Map<String, Object> root, ProviderIntegration provider, Path launcher, Path profile, EmptySessionStart whenEmpty) {
        Map<String, Object> group = object(root.computeIfAbsent(provider.hookGroup(), ignored -> new LinkedHashMap<>()));
        List<Object> preToolHooks = list(group.computeIfAbsent("PreToolUse", ignored -> new ArrayList<>()));
        boolean current = preToolHooks.stream().filter(provider::isManagedHook).count() == 1 && provider.managedHook(launcher, profile).equals(preToolHooks.stream().filter(provider::isManagedHook).findFirst().orElse(null));
        replaceManagedHooks(group, preToolHooks, list(group.get("SessionStart")), provider, launcher, profile, whenEmpty);
        return current;
    }

    private static void replaceManagedHooks(Map<String, Object> group, List<Object> preToolHooks, List<Object> sessionHooks, ProviderIntegration provider, Path launcher, Path profile,
            EmptySessionStart whenEmpty) {
        preToolHooks.removeIf(provider::isManagedHook);
        preToolHooks.add(provider.managedHook(launcher, profile));
        sessionHooks.removeIf(provider::isManagedSessionHook);
        Map<String, Object> sessionHook = provider.managedSessionHook(launcher, profile);
        if (sessionHook != null) {
            sessionHooks.add(sessionHook);
        }
        group.put("PreToolUse", preToolHooks);
        if (sessionHooks.isEmpty() && whenEmpty == EmptySessionStart.DROP) {
            group.remove("SessionStart");
        } else {
            group.put("SessionStart", sessionHooks);
        }
    }

    /**
     * Removes the provider's managed entries from the hook file under {@code projectRoot}, then drops
     * the keys and the file that the removal leaves empty.
     *
     * @return whether a managed entry was removed
     */
    static boolean removeManagedHooks(Path projectRoot, ProviderIntegration provider) throws IOException {
        Path config = provider.configurationPath(projectRoot);
        if (!Files.exists(config)) {
            return false;
        }
        if (provider.sharesHookFileWithUser() && (Files.isSymbolicLink(config) || !Files.isRegularFile(config, java.nio.file.LinkOption.NOFOLLOW_LINKS) || isTracked(projectRoot))) {
            throw providerConflict("tracked or non-regular hook cannot be rewritten");
        }
        Map<String, Object> root = readObject(config);
        Map<String, Object> group = object(root.get(provider.hookGroup()));
        if (group == null) {
            return false;
        }
        List<Object> hooks = list(group.get("PreToolUse"));
        boolean removed = hooks.removeIf(provider::isManagedHook);
        List<Object> sessionHooks = list(group.get("SessionStart"));
        boolean removedSession = sessionHooks.removeIf(provider::isManagedSessionHook);
        removed = removed || removedSession;
        if (sessionHooks.isEmpty()) {
            group.remove("SessionStart");
        } else {
            group.put("SessionStart", sessionHooks);
        }
        if (hooks.isEmpty()) {
            group.remove("PreToolUse");
        }
        if (group.isEmpty()) {
            root.remove(provider.hookGroup());
        }
        if (root.isEmpty()) {
            Files.deleteIfExists(config);
        } else {
            atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
        }
        return removed;
    }

    static void materializeHook(Path worktree, ProviderIntegration provider, Path launcher, Path profile) throws IOException {
        if (provider.sharesHookFileWithUser()) {
            materializeSharedHook(worktree, provider, launcher, profile);
            return;
        }
        Path config = provider.configurationPath(worktree);
        Map<String, Object> root = readObject(config);
        mergeManagedHooks(root, provider, launcher, profile, EmptySessionStart.KEEP);
        atomicWrite(config, ProviderJson.write(root) + System.lineSeparator());
    }

    private static void materializeSharedHook(Path worktree, ProviderIntegration provider, Path launcher, Path profile) throws IOException {
        Path config = provider.configurationPath(worktree).toAbsolutePath().normalize();
        Object lock = HOOK_LOCKS.computeIfAbsent(config, ignored -> new Object());
        synchronized (lock) {
            Path parent = config.getParent();
            if (parent == null || !parent.startsWith(worktree.toAbsolutePath().normalize())) {
                throw providerConflict("hook path is outside the worktree");
            }
            if (Files.exists(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw providerConflict("hook parent is not a regular directory");
            }
            if (Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (!parent.toRealPath().startsWith(worktree.toAbsolutePath().normalize().toRealPath())) {
                        throw providerConflict("hook parent escapes the worktree");
                    }
                } catch (IOException failure) {
                    throw providerConflict("hook parent cannot be verified");
                }
            }
            if (Files.isSymbolicLink(config) || (Files.exists(config, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(config, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw providerConflict("hook path is not a regular file");
            }
            if (isTracked(worktree)) {
                throw providerConflict("tracked hook file cannot be managed");
            }
            byte[] originalBytes = Files.exists(config) ? Files.readAllBytes(config) : null;
            Map<String, Object> root;
            if (originalBytes == null) {
                root = new LinkedHashMap<>();
            } else {
                try {
                    root = readObject(config);
                } catch (RuntimeException failure) {
                    throw providerConflict("hook JSON is malformed");
                }
            }
            Map<String, Object> group = object(root.get(provider.hookGroup()));
            if (root.containsKey(provider.hookGroup()) && group == null) {
                throw providerConflict("hook group ownership is ambiguous");
            }
            if (group == null) {
                group = new LinkedHashMap<>();
                root.put(provider.hookGroup(), group);
            }
            List<Object> preToolHooks = hookList(group, "PreToolUse");
            List<Object> sessionHooks = hookList(group, "SessionStart");
            for (Object value : preToolHooks) {
                if (sameMatcher(value, provider.matcher()) && !provider.isManagedHook(value)) {
                    throw providerConflict("pre-tool hook overlaps Synesis matcher");
                }
            }
            for (Object value : sessionHooks) {
                if (hasSynesisSessionId(value) && !canonicalSessionHook(value, provider)) {
                    throw providerConflict("session hook ownership is ambiguous");
                }
            }
            replaceManagedHooks(group, preToolHooks, sessionHooks, provider, launcher, profile, EmptySessionStart.KEEP);
            String updated = ProviderJson.write(root) + System.lineSeparator();
            if (Files.isSymbolicLink(config) || (Files.exists(config, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(config, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw providerConflict("hook path changed to a non-regular file");
            }
            if (Files.isSymbolicLink(parent) || (Files.exists(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw providerConflict("hook parent changed to a non-regular directory");
            }
            byte[] currentBytes = Files.exists(config) ? Files.readAllBytes(config) : null;
            if (!java.util.Arrays.equals(originalBytes, currentBytes)) {
                throw providerConflict("hook changed during materialization");
            }
            if (!java.util.Arrays.equals(originalBytes, updated.getBytes(StandardCharsets.UTF_8))) {
                atomicWrite(config, updated);
            }
        }
    }

    private static List<Object> hookList(Map<String, Object> group, String key) throws IOException {
        Object value = group.get(key);
        if (value == null) {
            return new ArrayList<>();
        }
        if (!(value instanceof List<?> list)) {
            throw providerConflict("hook list is malformed");
        }
        return new ArrayList<>(list);
    }

    private static boolean sameMatcher(Object value, String matcher) {
        return value instanceof Map<?, ?> map && matcher.equals(map.get("matcher"));
    }

    private static boolean hasSynesisSessionId(Object value) {
        return value instanceof Map<?, ?> map && "synesis-codex-session".equals(map.get("id"));
    }

    private static boolean canonicalSessionHook(Object value, ProviderIntegration provider) {
        if (!provider.isManagedSessionHook(value) || !(value instanceof Map<?, ?> map) || !"startup|resume".equals(map.get("matcher"))) {
            return false;
        }
        return map.get("hooks") instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof Map<?, ?> handler && "command".equals(handler.get("type")) && handler.get("command") instanceof String command
                && command.endsWith(" hook codex");
    }

    static boolean isTracked(Path worktree) throws IOException {
        try {
            return org.synesis.workspace.lifecycle.process.GitProcessRunner.runResult(worktree, "ls-files", "--error-unmatch", "--", ".codex/hooks.json").exitCode() == 0;
        } catch (IOException failure) {
            throw providerConflict("could not verify tracked state");
        }
    }

    static IOException providerConflict(String detail) {
        return new IOException("PROVIDER_CONFIGURATION_CONFLICT: " + detail);
    }
}
