package org.synesis.workspace.provider.codex;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.synesis.workspace.infrastructure.digest.Sha256;

/**
 * The Stop hook given to a headless Codex agent the run controller starts, for that run only (ADR-0099, 2026-10-18).
 *
 * <p>Codex 0.163 runs a hook only when it is trusted. A project's hooks run only after a person has trusted the
 * project and reviewed them, which a headless session has nobody to do; a hook given on the command line runs when
 * the same command line pins its trust to its hash. Synesis gives exactly its own Stop hook that way, with the hash of
 * exactly that hook: no other hook is trusted, nothing is written to the user's or the project's Codex
 * configuration, and the global {@code --dangerously-bypass-hook-trust} is never passed, since it would run every hook
 * a checkout carries.</p>
 *
 * <p>The trust key and the hash follow Codex's own recipe (codex-rs {@code hooks/src/engine/discovery.rs}
 * {@code hook_hash} and {@code synthetic_layer_path}, {@code config/src/fingerprint.rs} {@code version_for_toml}),
 * which the public documentation does not describe. A Codex that computes either differently sees the hook as
 * untrusted and skips it, without a word, and the agent is then woken by the run controller after it stops, as
 * before: the failure trusts nothing it should not.</p>
 */
final class CodexStopHook {

    /**
     * The hook's timeout in seconds: one minute beyond the longest hold, which ends the hook itself. Codex on Windows
     * does not stop a hook it has timed out, and {@code codex exec} waits for it.
     */
    static final int TIMEOUT_SECONDS = 660;

    /**
     * A launcher path Codex can run unquoted through any shell it uses (PowerShell, cmd, sh) and that a TOML literal
     * string holds as it is: no space, quote, or shell metacharacter.
     */
    private static final Pattern PLAIN_PATH = Pattern.compile("[\\p{L}\\p{N}._\\\\/:~+-]+");

    private CodexStopHook() {
    }

    /**
     * Returns the configuration overrides that give the session the hook and trust it, or nothing when the launcher's
     * path cannot be run unquoted; the agent is then woken by the run controller alone.
     *
     * @param launcher absolute Synesis launcher path
     * @param windows  whether Codex runs on Windows, which names the command-line layer differently
     * @return {@code --config} arguments, or an empty list
     */
    static List<String> arguments(Path launcher, boolean windows) {
        String path = launcher.toAbsolutePath().normalize().toString();
        if (!PLAIN_PATH.matcher(path).matches()) {
            return List.of();
        }
        String command = path + " hook codex";
        return List.of("--config", "hooks.Stop=[{hooks=[{type='command',command='" + command + "',timeout=" + TIMEOUT_SECONDS + "}]}]",
                "--config", "hooks.state={'" + key(windows) + "'={trusted_hash='" + hash(command) + "'}}");
    }

    /** Codex's key for the first Stop handler of the command-line layer. */
    static String key(boolean windows) {
        return (windows ? "C:\\<session-flags>\\config.toml" : "/<session-flags>/config.toml") + ":stop:0:0";
    }

    /**
     * Codex's hash of one Stop command handler: SHA-256 of the handler's normalized identity as compact JSON with
     * sorted keys. The command holds no character JSON escapes other than the backslash.
     */
    static String hash(String command) {
        String quoted = "\"" + command.replace("\\", "\\\\") + "\"";
        return "sha256:" + Sha256.hex("{\"event_name\":\"stop\",\"hooks\":[{\"async\":false,\"command\":" + quoted + ",\"timeout\":" + TIMEOUT_SECONDS + ",\"type\":\"command\"}]}");
    }
}
