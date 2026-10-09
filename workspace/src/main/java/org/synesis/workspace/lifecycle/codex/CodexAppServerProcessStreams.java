package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.synesis.workspace.lifecycle.codex.CodexAppServerLifecycleService.AppServerProcess;
import org.synesis.workspace.lifecycle.codex.state.CodexEvidenceJournal;

import static org.synesis.workspace.lifecycle.codex.CodexAppServerPayloadReader.diagnostic;

/**
 * Stateless stream handling for an App Server process: draining stderr into the evidence journal
 * and closing the stdio streams once ownership was lost.
 *
 * <p>Kept apart from {@link CodexAppServerLifecycleService} because both operations act only on
 * the process or journal passed in and never on lifecycle state.
 */
final class CodexAppServerProcessStreams {

    private CodexAppServerProcessStreams() {
    }

    static void closeStreams(AppServerProcess process) {
        try {
            process.process().getInputStream().close();
        } catch (IOException ignored) {
            // Best effort after ownership was lost.
        }
        try {
            process.process().getOutputStream().close();
        } catch (IOException ignored) {
            // Best effort after ownership was lost.
        }
    }

    static void drainStderr(InputStream stderr, CodexEvidenceJournal journal) {
        Thread thread = new Thread(() -> {
            try (stderr) {
                byte[] buffer = new byte[4096];
                int read;
                StringBuilder text = new StringBuilder();
                while ((read = stderr.read(buffer)) >= 0) {
                    text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (text.length() > 64 * 1024) {
                        text.delete(0, text.length() - 64 * 1024);
                    }
                    if (text.indexOf("\n") >= 0) {
                        journal.offer("process_stderr", Map.of("text", text.toString()), false);
                        text.setLength(0);
                    }
                }
                if (!text.isEmpty()) {
                    journal.offer("process_stderr", Map.of("text", text.toString()), false);
                }
            } catch (IOException failure) {
                journal.offer("process_stderr_failure", Map.of("diagnostic", diagnostic(failure)), true);
            }
        }, "synesis-codex-stderr-reader");
        thread.setDaemon(true);
        thread.start();
    }
}
