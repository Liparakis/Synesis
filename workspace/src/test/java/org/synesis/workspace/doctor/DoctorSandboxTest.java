package org.synesis.workspace.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.coordination.domain.policy.PolicyEntries.PolicyName;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectPolicyService;
import org.synesis.workspace.application.project.execution.sandbox.Sandbox;
import org.synesis.workspace.application.project.execution.sandbox.SandboxAvailability;
import org.synesis.workspace.application.project.execution.sandbox.SandboxPolicy;
import org.synesis.workspace.application.project.execution.sandbox.WrappedCommand;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleInventoryService;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupEligibilityService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;

/**
 * What doctor says about the sandbox and the unsandboxed-commands decision (ADR-0104, Part C): a missing or leaking
 * sandbox is a warning that carries the sandbox's own reason and fix, and a signed allowance is a warning that names
 * its revision. A working sandbox adds nothing.
 */
class DoctorSandboxTest {

  @TempDir
  Path temp;

  private String previousUserHome;

  @BeforeEach
  void isolateUserHome() throws Exception {
    previousUserHome = System.getProperty("user.home");
    System.setProperty("user.home", Files.createDirectories(temp.resolve("home")).toString());
  }

  @AfterEach
  void restoreUserHome() {
    System.setProperty("user.home", previousUserHome);
  }

  private static Sandbox sandbox(SandboxAvailability availability) {
    return new Sandbox() {
      @Override
      public String name() {
        return "stand-in";
      }

      @Override
      public SandboxAvailability probe() {
        return availability;
      }

      @Override
      public WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private DoctorReport diagnose(Path root, SandboxAvailability availability) {
    return new DoctorService(new ProjectApplicationService(), new LifecycleInventoryService(), new CleanupEligibilityService(), new SessionLeaseService(), new SessionLeaseStore(),
        ProcessInspector.system(), () -> temp.resolve("no-installer/Synesis/bin/synesis"), () -> true, name -> true, NetworkProbe.UNKNOWN, () -> sandbox(availability)).diagnose(root);
  }

  private Path project() throws Exception {
    Path root = Files.createDirectories(temp.resolve("project"));
    new ProjectApplicationService().init(root);
    return root;
  }

  private static Optional<DoctorFinding> find(DoctorReport report, DoctorFindingCode code) {
    return report.findings().stream().filter(finding -> finding.code() == code).findFirst();
  }

  @Test
  void aSandboxThatIsNotAvailableIsAWarningWithItsOwnReasonAndFix() throws Exception {
    DoctorReport report = diagnose(project(), SandboxAvailability.unavailable("no bwrap", "install bubblewrap"));

    DoctorFinding finding = find(report, DoctorFindingCode.SANDBOX_UNAVAILABLE).orElseThrow();
    assertEquals(DoctorSeverity.WARNING, finding.severity());
    assertEquals(DoctorGroup.ENVIRONMENT, finding.group());
    assertEquals("install bubblewrap", finding.fix());
    assertEquals(Map.of("sandbox", "stand-in", "reason", "no bwrap"), finding.details());
    assertEquals(DoctorStatus.DEGRADED, report.overallStatus());
  }

  @Test
  void aWorkingSandboxAddsNoFinding() throws Exception {
    DoctorReport report = diagnose(project(), SandboxAvailability.ready());

    assertTrue(find(report, DoctorFindingCode.SANDBOX_UNAVAILABLE).isEmpty());
    assertTrue(find(report, DoctorFindingCode.UNSANDBOXED_COMMANDS_ALLOWED).isEmpty());
  }

  @Test
  void aSignedAllowanceIsAWarningAndADenyClearsIt() throws Exception {
    Path root = project();
    var location = new ProjectApplicationService().locate(root);
    ProjectPolicyService.decide(location, newKey -> "a passphrase for tests".toCharArray(), Map.of(PolicyName.SANDBOX_UNSANDBOXED, "ALLOW"), Optional.empty());

    DoctorFinding allowed = find(diagnose(root, SandboxAvailability.ready()), DoctorFindingCode.UNSANDBOXED_COMMANDS_ALLOWED).orElseThrow();

    assertEquals(DoctorSeverity.WARNING, allowed.severity());
    assertTrue(allowed.details().containsKey("revision"), allowed.details().toString());
    assertTrue(allowed.fix().contains("deny-unsandboxed"), allowed.fix());

    ProjectPolicyService.decide(new ProjectApplicationService().locate(root), newKey -> "a passphrase for tests".toCharArray(), Map.of(PolicyName.SANDBOX_UNSANDBOXED, "DENY"), Optional.empty());
    assertTrue(find(diagnose(root, SandboxAvailability.ready()), DoctorFindingCode.UNSANDBOXED_COMMANDS_ALLOWED).isEmpty());
  }
}
