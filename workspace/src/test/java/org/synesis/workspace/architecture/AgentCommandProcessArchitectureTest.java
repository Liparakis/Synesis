package org.synesis.workspace.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0104 fitness function: {@code ProjectProcessExecutor} is the one place that starts an agent-authored command,
 * so it is the one sandbox choke point. Every other place in production code that starts a process is listed here
 * with what it starts. A new {@code ProcessBuilder} fails this test until a person has decided that it never runs a
 * command or code an agent wrote, and adds it to the list with its reason.
 */
class AgentCommandProcessArchitectureTest {

  private static final String EXECUTOR = "workspace/application/project/execution/ProjectProcessExecutor.java";

  /**
   * Files that start a process and the reason none of them starts agent-authored commands. Keys are paths under
   * {@code <module>/src/main/java/org/synesis/}.
   */
  private static final Map<String, String> OTHER_PROCESS_STARTERS = new TreeMap<>(Map.of(
      "workspace/application/project/execution/sandbox/LinuxBubblewrapSandbox.java",
      "the sandbox's own probe: a fixed script on fixed scratch folders",
      "workspace/application/project/execution/sandbox/MacSandboxExecSandbox.java",
      "the sandbox's own probe: a fixed script on fixed scratch folders, run only under the unverified-Mac switch",
      "workspace/lifecycle/process/ProcessCommandRunner.java",
      "Synesis's own Git (and fixed tools) with fixed arguments; the working folder is a worktree, hardened by GitProcessRunner",
      "workspace/application/agent/run/ProviderAgentProcesses.java",
      "launches the provider (claude, codex) that the person configured",
      "workspace/application/provider/ProviderDiscovery.java",
      "asks a provider binary for its version",
      "workspace/application/provider/ProviderHealthProbes.java",
      "runs a provider binary's own health probe",
      "workspace/lifecycle/codex/CodexAppServerDirectLaunch.java",
      "launches the Codex app server that the person configured",
      "workspace/lifecycle/codex/ManagedCodexProcessLauncher.java",
      "launches the managed Codex process that the person configured",
      "workspace/doctor/DoctorService.java",
      "runs {@code git --version}",
      "workspace/transport/control/ControlPlaneProjectEndpoints.java",
      "opens the operating system's folder picker for the person"));

  private static final Map<String, String> OTHER_MODULES = new TreeMap<>(Map.of(
      "cli/command/link/LinkFirewallRule.java", "runs netsh for the person's own firewall rule",
      "cli/daemon/BrowserLauncher.java", "opens the person's browser",
      "cli/daemon/CliChildJvm.java", "starts Synesis's own child JVM",
      "coordination/domain/speculation/SpeculationWorkspace.java", "runs Git for the speculation workspace with fixed arguments",
      "link/transport/quic/EphemeralTlsMaterial.java", "runs keytool for a fixed certificate"));

  @Test
  void onlyTheExecutorStartsAgentCommandsAndEveryOtherStarterIsListed() throws IOException {
    Path root = repositoryRoot();
    Map<String, String> allowed = new TreeMap<>(OTHER_PROCESS_STARTERS);
    allowed.putAll(OTHER_MODULES);
    allowed.put(EXECUTOR, "the one choke point");
    allowed.put("workspace/application/project/execution/sandbox/WindowsLowIntegrityLauncher.java",
        "the Windows sandbox's process creation: ProjectProcessExecutor starts every agent command through it, with a Low token");
    allowed.put("workspace/lifecycle/codex/WindowsJobObjectProcessTreeSupervisor.java",
        "launches the managed Codex process that the person configured, inside a job object");

    Map<String, Boolean> found = new TreeMap<>();
    try (Stream<Path> modules = Files.list(root)) {
      for (Path module : modules.toList()) {
        Path sources = module.resolve("src/main/java/org/synesis");
        if (!Files.isDirectory(sources)) {
          continue;
        }
        try (Stream<Path> files = Files.walk(sources)) {
          for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
            String text = Files.readString(file);
            if (text.contains("new ProcessBuilder(") || text.contains("Runtime.getRuntime().exec(") || text.contains("ProcessBuilder.startPipeline(")
                || text.contains("\"CreateProcessW\"") || text.contains("\"CreateProcessAsUserW\"")) {
              found.put(sources.relativize(file).toString().replace('\\', '/'), true);
            }
          }
        }
      }
    }
    // Every process starter must be known, and every listed one must still exist (a stale entry hides nothing).
    assertEquals(allowed.keySet(), found.keySet(), "a new process starter must be reviewed against ADR-0104, or a listed one is gone");
  }

  @Test
  void everyCallerOfTheExecutorGoesThroughItsDefaultSandboxAndNeverBuildsAnUnconfinedOne() throws IOException {
    Path root = repositoryRoot();
    List<String> offenders = new java.util.ArrayList<>();
    try (Stream<Path> files = Files.walk(root.resolve("workspace/src/main/java"))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String text = Files.readString(file);
        if (text.contains("UnconfinedForTests") || text.contains("UNCONFINED_FOR_TESTS_PROPERTY")) {
          if (!file.toString().replace('\\', '/').endsWith("sandbox/Sandboxes.java")) {
            offenders.add(file.toString());
          }
        }
      }
    }
    assertTrue(offenders.isEmpty(), "only Sandboxes may know the test-only unconfined sandbox: " + offenders);
  }

  private static Path repositoryRoot() {
    Path here = Path.of("").toAbsolutePath();
    return Files.isDirectory(here.resolve("src/main/java")) ? here.getParent() : here;
  }
}
