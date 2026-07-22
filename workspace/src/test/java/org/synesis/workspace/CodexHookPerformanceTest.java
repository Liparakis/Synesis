package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.synesis.projectrecord.ProjectConfig;
import org.synesis.workspace.integration.codex.CodexHookAdapter;
import org.synesis.workspace.provider.ProviderJson;

/** Measures the bounded Codex adapter over twenty in-process synthetic calls. */
final class CodexHookPerformanceTest {
    @Test
    void measuresTwentySyntheticInvocations() throws Exception {
        Path root = Files.createTempDirectory("codex-hook-perf-");
        try {
            var location = new org.synesis.workspace.application.ProjectApplicationService().init(root).location();
            new ProjectConfig(location.projectId(), java.util.Set.of("sl1-" + "0".repeat(64)))
                    .save(location.profile().resolve("project.conf"));
            String event = event(root);
            CodexHookAdapter adapter = new CodexHookAdapter();
            ArrayList<Long> samples = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                long start = System.nanoTime();
                assertEquals(CodexHookAdapter.Outcome.ALLOWED, adapter.processJson(event).outcome());
                samples.add(System.nanoTime() - start);
            }
            samples.sort(Long::compareTo);
            double p50 = samples.get(9) / 1_000_000.0;
            double p95 = samples.get(18) / 1_000_000.0;
            System.out.printf("CODEX_SYNTHETIC_INVOCATIONS=20%nCODEX_HOOK_LATENCY_P50_MS=%.3f%nCODEX_HOOK_LATENCY_P95_MS=%.3f%n",
                    p50, p95);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static String event(Path root) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "*** Begin Patch\n*** Add File: docs/performance.txt\n*** End Patch");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("cwd", root.toString());
        event.put("tool_name", "apply_patch");
        event.put("tool_input", input);
        return ProviderJson.write(event);
    }
}
