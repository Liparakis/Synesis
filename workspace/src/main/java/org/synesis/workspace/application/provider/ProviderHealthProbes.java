package org.synesis.workspace.application.provider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.synesis.mcp.contract.McpToolCatalog;
import org.synesis.projectrecord.domain.DecisionRecord;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.projectrecord.domain.ProjectConstraint;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.constraint.ConstraintApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderApplicationService.McpHealth;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;

import static org.synesis.workspace.application.provider.ProviderConfigurationFiles.object;

/**
 * Runs the checks that tell whether a provider integration actually works: a bounded MCP transport
 * probe against the installed launcher, the synthetic block-and-allow hook check in a throwaway
 * project, and verification of the project's decision records. Each one starts a process or creates
 * files, so the service reports their outcome without owning how they run. It is separate from
 * {@link ProviderApplicationService} so the timeouts and cleanup of those probes stay in one place.
 */
final class ProviderHealthProbes {

    private ProviderHealthProbes() {
    }

    static boolean recordStoreHealthy(ProjectLocation location) {
        Path records = location.profile().resolve("records");
        if (!Files.isDirectory(records)) {
            return false;
        }
        try (var paths = Files.walk(records)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".sdr")).toList()) {
                DecisionRecord record = DecisionRecord.decode(Files.readAllBytes(path));
                if (!record.verify()) {
                    return false;
                }
            }
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String readWithTimeout(BufferedReader reader) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return reader.readLine();
            } catch (IOException failure) {
                throw new RuntimeException(failure);
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private static boolean catalogNamesMatch(List<?> advertised) {
        if (advertised.size() != McpToolCatalog.rawNames().size()) {
            return false;
        }
        for (int index = 0; index < advertised.size(); index++) {
            if (!(advertised.get(index) instanceof Map<?, ?> descriptor) || !McpToolCatalog.rawNames().get(index).equals(descriptor.get("name"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Performs a bounded read-only MCP transport probe against the installed launcher.
     *
     * @param launcher native MCP launcher
     * @param provider provider identifier
     * @param project  project root supplied to the server
     * @return probe outcome
     */
    static McpHealth probeMcp(Path launcher, String provider, Path project) {
        Process process = null;
        try {
            List<String> command = List.of(launcher.toAbsolutePath().normalize().toString(), "mcp", "--provider", provider);
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("SYNESIS_MCP_PROJECT", project.toAbsolutePath().normalize().toString());
            process = builder.start();
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{" + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"synesis-installer\",\"version\":\"1\"}}}\n");
                writer.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n");
                writer.flush();
                String initialize = readWithTimeout(reader);
                String tools = readWithTimeout(reader);
                Map<?, ?> initializeMap = object(ProviderJson.parse(initialize));
                Map<?, ?> toolsMap = object(ProviderJson.parse(tools));
                Map<?, ?> initializeResult = object(initializeMap.get("result"));
                Map<?, ?> toolsResult = object(toolsMap.get("result"));
                Object advertised = toolsResult.get("tools");
                if (initializeResult.isEmpty() || !(advertised instanceof List<?> list) || !catalogNamesMatch(list)) {
                    return new McpHealth(false, "FAILED:unexpected_tools_or_initialize");
                }
                return new McpHealth(true, "PASSED");
            }
        } catch (TimeoutException failure) {
            return new McpHealth(false, "FAILED:timeout");
        } catch (Exception failure) {
            return new McpHealth(false, "FAILED:" + failure.getClass().getSimpleName());
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }

    static ProviderIntegration.SyntheticCheck syntheticCheck(ProviderIntegration provider) throws Exception {
        Path root = Files.createTempDirectory("synesis-provider-check-");
        try {
            Files.createDirectories(root);
            ProjectApplicationService projectService = new ProjectApplicationService();
            var fixture = projectService.init(root, false).location();
            UUID projectId = fixture.projectId();
            new ProjectConfig(projectId, java.util.Set.of("sl1-" + "0".repeat(64))).save(fixture.profile().resolve("project.conf"));
            new ConstraintApplicationService().create(fixture, "Synthetic protected file", "Synthetic check", "src/protected.txt", ProjectConstraint.Effect.BLOCK);
            return provider.syntheticCheck(fixture.profile(), fixture.root());
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}
